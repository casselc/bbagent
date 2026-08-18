# S0b SQLite Storage Findings

## 1. Verdict

**Pass, with a conditional switch recommendation.** SQLite implements the accepted
A0 durable event and content-addressed object semantics behind the same store
contract, passes the deterministic file/SQLite differential tests, and passes native
create, close, resume, replay, integrity, ambiguous-effect, transaction-crash, and
authority gates. At 10,000 events its indexed latest-checkpoint and ten-event tail
queries are dramatically lower than the file backend's complete scans.

The evidence justifies switching **new sessions** to SQLite after fresh review, while
retaining the EDN-lines backend as the readable reference and for existing file
sessions. It does not justify importing or silently converting existing sessions.
The implementation's current default remains `file`; callers must select SQLite with
`--store sqlite` or `:store-backend :sqlite` (`src/bbagent/storage.clj:13-36`,
`src/bbagent/core.clj:83-120`, `test/bbagent/agent_test.clj:36-68`). Thus the
recommendation among retain-file-default, switch, revise, or abandon is **switch for
new sessions after review**, not an immediate default change in this milestone, and
not abandonment of the file backend.

This verdict is bounded. Whole-history validation remains O(n), cumulative
checkpoints still repeat complete messages and replay forms, SQLite used more closed
storage in every measured workload, and append medians varied by workload. Those are
reasons to retain the reference backend and address long-session semantics, not to
discard the demonstrated indexed-query and transaction benefits.

## 2. Store Abstraction Implemented

`bbagent.store` defines root-level `EventStore`, `ObjectStore`, and `StoreLifecycle`
protocols (`src/bbagent/store.clj:17-52`). Their durable contract includes ordered
append, complete validation, event-tail and indexed semantic lookups, sorted session
listing, verified UTF-8 content-addressed objects, and idempotent close. Shared pure
helpers own secret stripping, identity/time/sequence preparation, large-string
externalization, hydration, canonical payload encoding, and semantic checksums
(`src/bbagent/store.clj:54-219`). The file and SQLite implementations therefore share
logical semantics rather than duplicating event-format policy.

`bbagent.storage/open!` is the only backend factory used by session and CLI code. It
accepts `file` or `sqlite`, rejects other values as `:journal-storage-failure`, and
maps `nil` to `file` (`src/bbagent/storage.clj:11-36`). `session/start!` and
`session/resume!` also default to `:file` and pass the selected root store through the
agent lifecycle (`src/bbagent/session.clj:65-95,181-248`).

The default state root is `~/.local/state/bbagent`, overridable by `--state` and then
`BBAGENT_STATE_ROOT` (`src/bbagent/core.clj:13-14,61-63`). Physical locations are:

| Backend | State-root path |
|---|---|
| File | `STATE_ROOT/sessions/SESSION_ID/events.edn` and `.../blobs/SHA256` |
| SQLite | `STATE_ROOT/bbagent.sqlite3` |

`run`, `resume`, `sessions`, and `inspect` expose explicit `--store file|sqlite`;
usage states that the default is file (`src/bbagent/core.clj:174-183`). Selection is
not inferred. An existing file session must continue to be opened with `file`, while
a SQLite session must be opened with `sqlite`.

`inspect` prints each hydrated logical event with `prn`, one EDN value per line
(`src/bbagent/core.clj:115-120`). Redirecting that output provides a backend-neutral
EDN inspection/export stream; the final native wrapper inspected all 49 events and
the inspect output SHA-256 is
`2d6d8c34aac5ba923b7ef478ff2b7363977def7f632c6a3dd68193fddffc8d20`.
This is a logical export, not a physical SQLite backup. There is no data-import,
file-to-SQLite migration, SQLite-to-file migration, or export-import round trip in
the current CLI or store protocols.

## 3. Physical Schema

Schema version 1 uses two `STRICT` tables and no separate checkpoint table
(`src/bbagent/sqlite_store.clj:23-29`):

| Table | Columns and constraints |
|---|---|
| `event` | `session_id TEXT NOT NULL`, `seq INTEGER NOT NULL CHECK (seq > 0)`, `event_id TEXT NOT NULL UNIQUE`, `event_type TEXT NOT NULL`, `event_time TEXT NOT NULL`, nullable `request_id` and `action_id`, `payload BLOB NOT NULL`, `checksum TEXT NOT NULL`, primary key `(session_id, seq)` |
| `object` | `digest TEXT PRIMARY KEY`, `bytes INTEGER NOT NULL CHECK (bytes >= 0)`, `encoding TEXT NOT NULL`, nullable `media_type`, `content BLOB NOT NULL` |

`event_session_type_seq(session_id, event_type, seq DESC)` supports first/latest
type selection, including latest checkpoint. Partial index
`event_request(session_id, request_id) WHERE request_id IS NOT NULL` supports request
correlation. Event IDs are unique across the complete state root, not merely within a
session. Objects are also state-root-wide: the primary key is the unprefixed lowercase
SHA-256 hex digest, while public references use `sha256:<hex>`.

There are no foreign-key declarations, despite enabling foreign-key enforcement.
Object references remain inside the canonical event payload rather than in a join
table; there are no reference counts, object garbage collection, checkpoint rows,
FTS tables, or vector tables. Session existence is derived from event rows, so an
unreferenced object alone does not create a logical session
(`test/bbagent/store_contract_test.clj:250-270`).

### Canonical payload and checksum

The two fields are deliberately distinct:

- `payload` is the UTF-8 bytes of `coordinates/canonical-string` for the **stored,
  externalized event**. The reversible canonical tree preserves supported EDN data
  and makes map/set ordering independent (`src/bbagent/store.clj:174-219`,
  `src/bbagent/coordinates.clj:9-66`).
- `checksum` is the domain-separated semantic digest
  `coordinates/digest :bbagent/journal-event stored-event`. It is not a hash of the
  raw `payload` bytes (`src/bbagent/store.clj:94-97`).

On read, SQLite decodes the canonical payload, recomputes the semantic checksum,
checks the denormalized type, sequence, event ID, time, request ID, and action ID
against the decoded event, and only then hydrates object references
(`src/bbagent/sqlite_store.clj:191-212`). Tests independently corrupt payload and
checksum and require `:session-recovery-failure`
(`test/bbagent/sqlite_store_test.clj:154-173`).

## 4. Schema Migration and Versioning

`PRAGMA user_version` is `1`. A database at version `0` receives all schema statements
and `user_version = 1` in one explicit transaction. Failure rolls back both DDL and
version changes (`src/bbagent/sqlite_store.clj:47-77,112-143`). A test migration that
creates a table, sets version 99, and throws leaves version 1, prior data, and no new
table (`test/bbagent/sqlite_store_test.clj:237-259`).

This is safe fresh-schema creation and a tested transaction mechanism, not a general
migration system. Version 1 is the only implemented schema. A newer version is
rejected as `:journal-storage-failure`; there is no ordered migration registry,
downgrade, data rewrite, or import path. In particular, S0b does **not** migrate A0
EDN-lines journals into SQLite.

## 5. PRAGMA and Durability Policy

Every opened connection applies and reads back this policy before use
(`src/bbagent/sqlite_store.clj:79-102`):

| Setting | Required value | Read-back value |
|---|---:|---:|
| `journal_mode` | `WAL` | `wal` |
| `synchronous` | `FULL` | `2` |
| `foreign_keys` | `ON` | `1` |
| `busy_timeout` | 5000 ms | `5000` |

Opening fails if the required values are not observed. This is a conservative local
SQLite policy, but it is not evidence of filesystem-independent durability, a pinned
storage stack, or tested multi-process writer throughput. The implementation owns one
managed `java.sql.Connection`, serializes operations with an in-process lock, and
uses the busy timeout for external contention (`src/bbagent/sqlite_store.clj:34,
112-143`).

## 6. Transaction-Boundary Model

Each SQLite event append is one `BEGIN IMMEDIATE` transaction
(`src/bbagent/sqlite_store.clj:235-272`):

1. Read the session's maximum sequence and assign the next contiguous value.
2. Strip secrets and prepare identity/time/sequence.
3. Externalize over-threshold strings, inserting required objects on the same
   connection and in the same transaction.
4. Insert the event row with canonical payload and semantic checksum.
5. Commit; on any failure, roll back event and newly staged objects.

The duplicate-ID test proves that a newly staged object rolls back while the prior
event and object remain usable (`test/bbagent/sqlite_store_test.clj:201-226`). Explicit
`put-object!` uses its own transaction (`src/bbagent/sqlite_store.clj:415-424`). Schema
creation also has its own transaction.

The boundary is one durable event, not a complete model turn, REPL evaluation, or
checkpoint. The accepted A0 effect protocol remains: durable `:repl/request`, perform
the external SCI effect, durable `:repl/result`, then checkpoint. A crash after intent
but before result is semantically ambiguous and recovery fails closed
(`src/bbagent/session.clj:97-179,297-318`). SQLite cannot make an external SCI effect
atomic with a database commit.

Native failure probes exercised both boundaries:

| Probe | Cut and result |
|---|---|
| Ambiguous effect | Hard exit 73 after durable request intent; recovery returned `:failed-closed`, category `:session-recovery-failure`, message `A REPL effect was interrupted before its result was durable` |
| Uncommitted transaction | Hard exit 74 after inserting an object without commit; reopening retained one baseline event and reported `:uncommitted-object/visible? false` |
| Missing native sidecar | Exit 1 and no database created |

These prove the tested cuts. They do not constitute exhaustive crash injection across
every SQLite, WAL, checkpoint, filesystem, or power-loss boundary.

## 7. CAS Semantics

Strings larger than 65,536 UTF-8 bytes are recursively replaced by
`#bbagent/blob {:digest "sha256:..." :bytes N :encoding :utf-8}`. Strings exactly at
the threshold stay inline (`src/bbagent/store.clj:54-56,99-172`,
`test/bbagent/store_contract_test.clj:87-97,199-212`). Explicit object puts use the
same reference shape at any size.

SQLite stores UTF-8 content once by digest. Re-putting identical content is
idempotent; a pre-existing row whose length, encoding, media type, or content conflicts
with the computed object fails as `:journal-storage-failure`. Reads require a valid
digest, an existing row, matching byte count, `utf-8`, nil media type, and a matching
content SHA-256, otherwise recovery fails closed
(`src/bbagent/sqlite_store.clj:150-189,415-462`). Missing and corrupt object tests,
cross-session lookup, duplicate insertion, and event-plus-object rollback pass.

CAS identity is state-root-wide and independent of session ownership. The current
schema has no reachability tracking or collection. Canonical event payloads contain
the references; the semantic checksum covers those references, while object content
is verified by its own digest and byte metadata.

## 8. File-vs-SQLite Parity

The parameterized public-contract test runs both backends only through
`bbagent.storage`, `bbagent.store`, and the session API
(`test/bbagent/store_contract_test.clj:1-18`). Normalized observations are identical
for:

- ordered append, assigned and caller-supplied identity/time, contiguous sequence,
  duplicate ID rejection, and reopen continuation;
- recursive secret stripping;
- 65,536-byte inline and 66,000-byte externalized multibyte payloads;
- CAS put/get/idempotence and state-root-wide lookup;
- sorted session listing and absent sessions;
- first event, latest checkpoint, request correlation, and events after a cursor;
- missing/malformed object and unknown-event failures at a stable fail-closed semantic
  level;
- failed-form replay, durable result-tail folding, and unresolved request failure.

Agent end-to-end, restart/resume, failed-form mutation replay, tail recovery, and
coordinate preservation tests also execute both backends
(`test/bbagent/agent_test.clj:70-300`). The deterministic result recorded for the
implementation is **55 tests, 408 assertions, 0 failures, 0 errors** under
`clojure -M:test`; test-log SHA-256 is
`ede61edf3ad22cb163dc36928aff03805e0e85615c23bf13418cb415db955aba`.

Parity is logical, not physical. The file backend retains its EDN-lines/torn-final-line
repair behavior, while SQLite relies on database/WAL transactions. Error mechanisms
may differ where the differential test intentionally compares the shared fail-closed
category rather than an identical low-level exception.

## 9. Native Evidence

### Coordinates and toolchain

| Coordinate | Exact value |
|---|---|
| bbagent implementation | `ad6c58549870684e397b46f12e7e96870ca977a6` |
| build wrapper/builder | `852494260e5540001ed3a723deb94ee3265e8c1f` |
| measurements | `0b44dd0d2bb4f01f0cb1aeec5052bdab8b98c1c9` |
| final JVM test run | `852494260e5540001ed3a723deb94ee3265e8c1f` |
| bb4t | `f438307280b7a01fd20e99f54cd82682ef15d12a` |
| next.jdbc | `com.github.seancorfield/next.jdbc:1.3.1118` |
| sqlite-jdbc | `org.xerial/sqlite-jdbc:3.53.2.1` |
| SQLite runtime | `3.53.2` |
| Leiningen | `2.11.2` |
| Java | `25.0.4+7-LTS` |
| GraalVM distribution | `Oracle GraalVM 25.2.4+7.1` |
| native-image | `25.0.4` |

As in A0, `25.0.4` is the JDK/native-image language version and `25.2.4+7.1` is the
Oracle GraalVM distribution release. Source and direct dependency coordinates are
pinned; this is not a fully toolchain-pinned reproducible build.

The measurement commit contains the complete store/session/test implementation. The
only production-source change from it to the native implementation commit is the
non-vacuous native CAS evidence event in `bbagent.s0b-smoke`; store and session
semantics are unchanged. The final JVM tests ran at the builder commit, whose changes
after the native implementation are confined to `script/build-native`.

The native-image phase took 41.5 s; wrapper wall time was 251.82 s; maximum RSS was
3,973,436 KiB; 22 warnings were recorded. Build-log SHA-256 is
`7eebff204871b23ad15723ee9f7c5ff92ba5eb74ec1fcd30a8c14d6488ed4a81`.

### Final native pass

The relocated distribution created SQLite session `s0b-native-main` in run
`6539b195-e66a-4ea0-8385-0dd34a325121`, closed at 28 contiguous events, and verified
the required oversized object
`sha256:bca09f4a757d5571c7d9f3341d4301f3c391c090826acc1a3013c6bcb7c01722`.
A new process resumed it in run `e2910471-ccf0-4f3e-b647-ae1d618bf8c1`, reconstructed
the retained value with count 70,000, closed at 49 events, and reverified the same
object. Session listing and all-event EDN inspection passed. The closed database was
200,704 bytes with SHA-256
`9d54c072b47ba465844857072607aea3058965398544c1b710e220c0f4134414`.
Database/inspect sizes, the 49-line inspect count, and the complete distribution size
are retained in `post-build-measurements.txt`, SHA-256
`8956fc2abdf6e14a023deb878e45076d2b34a8c43e78388dd6d47ae00417c91f`.

The wrapper also passed the two hard-exit probes, relocated sidecar loading, absent
sidecar failure, and a check that the designated runtime temporary directory remained
empty. Native evidence proves these product paths, not complete JVM/native suite
parity.

### Preliminary native proof failures

Two preliminary wrapper runs failed their gates before the final pass:

1. The first CAS verifier assumed normal agent events necessarily contained the raw
   project content and tried to verify that inferred object. The object lookup failed
   instead of allowing a vacuous CAS claim. Implementation
   `ad6c58549870684e397b46f12e7e96870ca977a6` made the proof non-vacuous by appending
   an explicit oversized `:s0b/native-object` event before verification
   (`src/bbagent/s0b_smoke.clj:46-75`).
2. The next image produced the correct reconstructed value, but the wrapper's textual
   assertion searched for `:data 70000`; EDN printed the namespaced key as
   `:value/data 70000`. Builder
   `852494260e5540001ed3a723deb94ee3265e8c1f` corrected that assertion
   (`script/build-native:128-135`).

Neither preliminary run falsely passed: the first terminated on object verification,
and the second terminated at the wrapper evidence assertion. The corrected final
wrapper passed.

### Artifact hashes

| Artifact/evidence | Bytes | SHA-256 |
|---|---:|---|
| `dist/bbagent` | 65,014,016 | `8c606778779c3b501f9c406ffb7c831dc4bbec3a5a8c14c8a805b88ee5989b3f` |
| `dist/libsqlitejdbc.so` | 1,093,888 | `f374da845a36d0a663521457f8e454413325e3b8247a15c2677426f4b15cf6ac` |
| `dist/THIRD_PARTY_NOTICES.md` | 2,141 | `8d26e1419db5020baedacdf10e2a0464d979a958dd83f429d7e466fc9adb47b5` |
| `s0b-create.edn` | - | `8305c852a2689781311eb32dcaeb58475e7f23d4b150e4627062a8bf2f708844` |
| `s0b-resume.edn` | - | `9827f4d69aeb4a0aef0bbd717b5b6ba654bfed5cbf7184ce729274aab9e52756` |
| `s0b-ambiguous-check.edn` | - | `b6a17e84c70af9a70f428f0c721ced95b7b80fea1d9b4c2b5874c0a48ddd340a` |
| `s0b-transaction-check.edn` | - | `f129bdff5113785fdd38d204e7e1a6c7a87d18096e4bfde00c56af7978e86fd0` |
| `s0b-authority.edn` | - | `ff1378c42484107b9bf4b46b35f0bda8d2e9f396794f4d116a86bb39057c1f5d` |

Executable and sidecar modes were `0755`; complete distribution size was 66,137,201
bytes. The build manifest additionally records native/relocated smoke, session list,
inspect, hard-exit setup, database, notices, and license hashes
(`/tmp/opencode/bbagent-s0b-20260818c/build-evidence.txt`).

## 10. Authority Negatives

SQLite is reachable only from trusted host code. The A0 `:agent/project-read`
ContextSpec and its three grants remain unchanged. The native authority gate passed
four positive probes and 18 negative probes. Every negative returned status `:error`
and category `:bb4t-evaluation-failure`; projected Java class count and supplied import
count were both zero, and no forbidden database was created.

The negative set includes `java.sql` classes and `DriverManager`, xerial classes,
`next.jdbc`, `bbagent.sqlite`, `bbagent.store`, `bbagent.storage`,
`bbagent.sqlite-store`, and S0b smoke implementation entry points
(`src/bbagent/sqlite.clj:128-223`). Tests also assert the unchanged ContextSpec,
effective grants, zero projected classes/imports, common failure category, and absent
forbidden database (`test/bbagent/integration_test.clj:46-64`). No database path,
connection, SQL/JDBC function, store handle, host namespace, or implementation
function is projected into model SCI.

This preserves A0's semantic restriction; it does not add hard process isolation or
hostile-code containment.

## 11. Performance and Storage Measurements

The measurements are local engineering measurements, not benchmarks: one generated
session per size, one sample per size, and append percentiles taken within that
session (`artifacts/s0b-measurements.edn`). The 10,000-event result is:

| Operation | File | SQLite | Interpretation |
|---|---:|---:|---|
| Append median | 0.041 ms | 0.090 ms | File lower in this workload |
| Append p95 | 0.046 ms | 0.108 ms | File lower in this workload |
| Append total | 425.5 ms | 929.2 ms | SQLite paid per-transaction cost |
| Full read | 417.7 ms | 331.2 ms | Both read the whole history |
| Streaming validation | 458.6 ms | 314.0 ms | Both remain O(n) |
| Latest checkpoint | 416.7 ms | 0.297 ms | SQLite index avoids the file scan |
| Ten-event tail | 416.2 ms | 0.474 ms | SQLite cursor query avoids the file scan |
| Reopen | 418.8 ms | 0.729 ms | File open recovers the complete journal |
| Validate + checkpoint + tail | 1,230.3 ms | 311.4 ms | SQLite still pays O(n) validation |
| CAS put / get | 0.865 / 0.496 ms | 0.238 / 0.136 ms | Local result only |
| Closed storage | 3,164,505 bytes | 7,880,704 bytes | SQLite was 2.49x larger |

The checkpoint lookup was about 1,400x lower and the ten-event tail about 880x lower
in this particular 10k run. These ratios describe the recorded local run, not a
portable service-level claim. Full history streaming validation still decodes,
checks, and hydrates every row and remains O(n); SQLite's `jdbc/plan` avoids building
the complete event vector but still accumulates event IDs for duplicate detection
(`src/bbagent/sqlite_store.clj:292-317`).

Append medians are not uniformly ordered: file/SQLite were 0.249/0.227 ms at 100
events, 0.097/0.113 ms at 1,000, and 0.041/0.090 ms at 10,000. They vary with local
warmup, filesystem, WAL, and within-session effects and must remain labeled local
engineering measurements.

SQLite closed storage was larger at every measured size: 172,032 versus 99,801 bytes
at 100 events, 864,256 versus 373,503 at 1,000, and 7,880,704 versus 3,164,505 at
10,000. Live SQLite storage was larger again because the measurement included live
WAL-related files: 11,943,632 bytes at 10,000 versus the 7,880,704-byte closed size.

The cumulative-checkpoint workload stored 1,000 messages, a complete checkpoint every
100 messages, and 1,010 total events. SQLite latest-checkpoint lookup was 6.33 ms
versus 61.80 ms for file, but validation was effectively the same order (59.31 ms
versus 56.81 ms), and closed SQLite storage was 1,224,704 bytes versus 472,768. The
index finds the row quickly; it does not make an increasingly large checkpoint cheap
to decode, validate, or store.

## 12. Remaining Long-Session Debt

S0b improves physical selection without changing A0 checkpoint semantics. Every
checkpoint still repeats all current provider-neutral messages and all replay forms
(`src/bbagent/session.clj:58-63`). Resume first performs complete-history validation,
then selects the latest checkpoint and tail (`src/bbagent/session.clj:181-205`).
Consequently:

- validation remains O(n) in event count and hydrates referenced objects;
- checkpoint payload size grows with the complete retained conversation and replay
  program;
- repeated cumulative checkpoints consume superlinear total bytes over a long
  session even though lookup is indexed;
- recovery replays every retained form and still reconstructs SCI state rather than
  persisting a heap;
- there is no checkpoint compaction, incremental snapshot format, object reachability
  tracking/GC, retention policy, or archival boundary.

The next storage revision should address incremental/content-addressed checkpoint
state and bounded validation/recovery before claiming long-session scalability.
Those changes require a separate semantic review; they should not be hidden inside a
default-backend flip.

## 13. Recommendation and Explicit Exclusions

After fresh review, make SQLite the backend for newly created sessions because the
contract, deterministic suite, native lifecycle, crash probes, and authority negatives
pass, while indexed checkpoint/tail access materially addresses the file backend's
measured scan cost. Keep file support as the accepted, human-readable reference and
for sessions already stored there. Require explicit backend selection for existing
sessions until a separately designed migration exists. The code at this finding still
defaults to file; S0b itself does not change that default.

Do not represent this recommendation as implementing any of the following:

- file/SQLite import, automatic migration, downgrade, or merged session discovery;
- a general schema migration registry beyond transactional version-1 creation;
- complete crash, power-loss, network-filesystem, or concurrent multi-process proof;
- complete JVM/native test-suite parity or fully toolchain-pinned reproducibility;
- bounded-size or incremental checkpoints, object GC, archival, replication, or
  backup policy;
- model-visible SQL/JDBC, database paths, trusted storage namespaces, or host handles;
- FTS, vectors, general memory, project editing, process/network authority, additional
  storage systems, or encryption;
- TUI, prompt/model-loop redesign, multi-agent behavior, A1, or broad BB2 work.

Stop here for S0b findings and fresh review. Do not begin A1 as part of this decision.
