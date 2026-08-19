# S0b SQLite Storage Findings

## 1. Verdict

**Pass, with a conditional switch recommendation.** SQLite implements the accepted
A0 durable event and content-addressed object semantics behind the same store
contract, passes the deterministic file/SQLite differential tests, and passes native
create, close, resume, replay, integrity, ambiguous-effect, transaction-crash, and
authority gates. SQLite preserves indexed query behavior across process boundaries;
the file backend now serves repeated queries from a per-process recovered cache but
still pays complete O(n) recovery on first access after reopen.

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

### Adversarial follow-up

The three A1-blocking findings in
`/home/chuck/opencode/src/S0AB_ADVERSARIAL_RUNTIME_STORAGE_REVIEW.md` are closed by
implementation `dd9608ed0e1182abe4ecc73cbd8ae7180141a7f9`:

1. Both backends verify every caller-supplied `#bbagent/blob` in the event positions
   interpreted by hydration before committing its event. The file backend also copies
   a verified state-root-wide object into the target session's physical blob
   directory. Missing, malformed, and corrupt references in those positions fail
   before an event becomes durable.
2. Resume audits unresolved model and REPL effects across the complete history before
   checkpoint/tail reconstruction, so a later checkpoint cannot hide an unresolved
   request.
3. A successful provider call followed by response-persistence failure leaves the
   durable request unresolved. It no longer appends a false provider-error response
   that could make recovery appear complete.

The same correction set rejects every unsupported schema version before persistent
PRAGMA changes, reads supported foreign tagged literals in file journals, uses
null-safe SQLite action correlation, gates the complete next.jdbc and sqlite-jdbc JAR
digests, and strengthens the transaction crash probe to stage both an object and its
referencing event. The deterministic suite, relocated native wrapper, and refreshed
engineering measurements all ran after these changes.

### Second-pass follow-up

The ten findings in
`/home/chuck/opencode/src/S0AB_ADVERSARIAL_RUNTIME_STORAGE_REVIEW_2.md` were addressed
without beginning A1 or BB2. Implementation
`1798dcd0e92a4b68facd8087ea863d8c8aacc3fa` makes file-session recovery lazy and
isolated, enforces one file-store owner per state root, uses the recovered event cache,
keeps root-wide event-ID uniqueness behind a lazy fail-closed audit, and fsyncs created
directory entries on the supported Linux target. SQLite schema initialization,
append, explicit object put, and test migration now share one `BEGIN IMMEDIATE`
transaction helper. A version-zero database is initialized only when
`main.sqlite_schema` is empty; a foreign version-zero database is rejected before WAL
or other policy PRAGMAs.

Native runtime now verifies and selects the executable-adjacent sqlitejdbc sidecar
before connection or database creation. Opaque map-key/foreign-tag positions are
pinned by differential tests, the exit-73 probe is an actual unclean halt, create and
resume both require non-vacuous CAS evidence, and the authority set includes 22
negatives. Physical executable provenance remains deliberately outside durable
session coordinates and is still deferred to BB2.

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

The file implementation is explicitly single-owner. Opening a `FileStore` acquires an
exclusive advisory lock at `STATE_ROOT/.bbagent-file-store.lock`; another cooperating
process or store instance cannot open that root until close or process death releases
the lock. Protocol reads recover each requested session once and use the cached
hydrated events thereafter. Session listing reads only journal metadata, so corruption
in one session does not block listing or healthy-session reads. Before its first append,
the store audits all complete records without hydrating blobs to establish root-wide
event-ID uniqueness. If corruption prevents that audit, reads remain available but
all appends fail closed until the store is repaired and reopened. Low-level
`bbagent.journal/open!` remains a single-owner primitive and does not acquire the
root-store lock.

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
`9b084e41f82a96c0c3b5e26683ee6f5baaa2087d8dac0d9b820f6d70c770f25a`.
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
(`src/bbagent/sqlite_store.clj:217-238`). Tests independently corrupt payload and
checksum and require `:session-recovery-failure`
(`test/bbagent/sqlite_store_test.clj:202-221`).

## 4. Schema Migration and Versioning

`PRAGMA user_version` is `1`. A database at version `0` receives all schema statements
and `user_version = 1` in one `BEGIN IMMEDIATE` transaction only if
`main.sqlite_schema` is empty under that transaction. Failure rolls back both DDL and
version changes. A pre-existing empty database is accepted; a foreign version-zero
database with any schema object is rejected while retaining version 0, `DELETE`
journal mode, schema, and rows. A concurrent opener that initialized first is accepted
after the in-transaction version recheck. A test migration that creates a table, sets
version 99, and throws leaves version 1, prior data, and no new table.

This is safe fresh-schema creation and a tested transaction mechanism, not a general
migration system. Version 1 is the only implemented schema. Every version outside
`#{0 1}`, including a negative or newer version, is rejected as
`:journal-storage-failure` before connection PRAGMAs can make persistent changes;
version zero is accepted only for an empty schema, also before policy PRAGMAs. A
foreign database that independently claims `user_version = 1` is not identified by
this marker. There is no ordered migration registry, downgrade, data rewrite, or
import path. In particular, S0b does **not** migrate A0 EDN-lines journals into SQLite.

## 5. PRAGMA and Durability Policy

Every opened connection applies and reads back this policy before use
(`src/bbagent/sqlite_store.clj:109-132`):

| Setting | Required value | Read-back value |
|---|---:|---:|
| `journal_mode` | `WAL` | `wal` |
| `synchronous` | `FULL` | `2` |
| `foreign_keys` | `ON` | `1` |
| `busy_timeout` | 5000 ms | `5000` |

Opening fails if the required values are not observed. This is a conservative local
SQLite policy, but it is not evidence of filesystem-independent durability, a pinned
storage stack, or measured multi-process writer throughput. Each store instance owns
one managed `java.sql.Connection` and serializes its operations with an in-process
lock. Cross-process writers coordinate through `BEGIN IMMEDIATE` and the busy timeout;
no fairness or sustained-contention claim is made.

## 6. Transaction-Boundary Model

Every SQLite write path uses the same raw-SQL `BEGIN IMMEDIATE` helper: schema
initialization, event append, explicit object put, and test migration. No write path
uses JDBC autocommit transaction control. A failed `BEGIN` owns no transaction and
does not issue rollback; a body or commit failure rolls back and preserves any
rollback error as suppressed context. Each event append performs:

1. Read the session's maximum sequence and assign the next contiguous value.
2. Strip secrets and prepare identity/time/sequence.
3. Verify caller-supplied object references, then externalize over-threshold strings,
   inserting required objects on the same connection and in the same transaction.
4. Insert the event row with canonical payload and semantic checksum.
5. Commit; on any failure, roll back event and newly staged objects.

The duplicate-ID test proves that a newly staged object rolls back while the prior
event and object remain usable. Explicit `put-object!` and schema creation each have
their own immediate transaction. A nested-acquisition test proves that failed `BEGIN`
does not roll back a transaction it did not acquire.

The boundary is one durable event, not a complete model turn, REPL evaluation, or
checkpoint. The accepted A0 effect protocol remains: durable `:repl/request`, perform
the external SCI effect, durable `:repl/result`, then checkpoint. A crash after intent
but before result is semantically ambiguous and recovery fails closed
(`src/bbagent/session.clj:97-179,297-318`). SQLite cannot make an external SCI effect
atomic with a database commit.

Native failure probes exercised both boundaries:

| Probe | Cut and result |
|---|---|
| Ambiguous effect | Unclean hard exit 73 immediately after durable request intent, with subscription and SQLite connection still open; recovery returned `:failed-closed`, category `:session-recovery-failure`, message `An external effect was interrupted before its result was durable` |
| Uncommitted transaction | Hard exit 74 after staging an object and its referencing event without commit; reopening retained one baseline event, no staged event, and reported `:uncommitted-object/visible? false` |
| Missing native sidecar | Runtime identity gate returned exit 1 and no database was created |
| Digest-mismatched native sidecar | Runtime identity gate returned exit 1 with explicit SHA-256 mismatch and no database was created |

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
(`src/bbagent/sqlite_store.clj:176-215,479-533`). Missing and corrupt object tests,
cross-session lookup, duplicate insertion, and event-plus-object rollback pass.

CAS identity is state-root-wide and independent of session ownership. The current
schema has no reachability tracking or collection. Canonical event payloads contain
the references; the semantic checksum covers those references, while object content
is verified by its own digest and byte metadata. Caller-supplied references are
validated before append on both backends; for the per-session file layout, verified
cross-session objects are copied into the target session before its event is appended.
Map keys and the contents of foreign tagged literals are opaque to both reference
validation and hydration; a blob-shaped value there is retained as ordinary data, not
interpreted as a CAS reference.

The file backend writes and forces object content, atomically renames it, and forces
the destination directory before an event can reference it. It also forces newly
created directory-parent entries and the parent of a newly created journal. These
Linux filesystem operations preserve the intended object-before-event ordering; they
do not claim network-filesystem or hardware-independent power-loss durability.

## 8. File-vs-SQLite Parity

The parameterized public-contract test runs both backends only through
`bbagent.storage`, `bbagent.store`, and the session API
(`test/bbagent/store_contract_test.clj:1-18`). Normalized observations are identical
for:

- ordered append, assigned and caller-supplied identity/time, contiguous sequence,
  duplicate ID rejection, and reopen continuation;
- recursive secret stripping in the interpreted event tree;
- 65,536-byte inline and 66,000-byte externalized multibyte payloads;
- CAS put/get/idempotence, state-root-wide lookup, and pre-commit validation of
  caller-supplied references;
- sorted session listing and absent sessions;
- first event, latest checkpoint, request correlation, and events after a cursor;
- missing/malformed object and unknown-event failures at a stable fail-closed semantic
  level;
- failed-form replay, durable result-tail folding, unresolved requests hidden before
  checkpoints, and provider-response persistence failure.

Agent end-to-end, restart/resume, failed-form mutation replay, tail recovery, and
coordinate preservation tests also execute both backends
(`test/bbagent/agent_test.clj:70-300`). The deterministic result recorded for the
implementation is **68 tests, 488 assertions, 0 failures, 0 errors** under
`clojure -M:test`; test-log SHA-256 is
`ed90cb52e56a2edb53203faeeee86250370128dd99840b9e74f0ef4a66b08fad`.

Parity is semantic, not operational. The file backend retains its
EDN-lines/torn-final-line repair behavior, one exclusive owner per root, lazy
per-session recovery, and process-local hydrated cache. SQLite relies on database/WAL
transactions, isolates corrupt rows by queried session, and can coordinate independent
writer connections through immediate transactions. File operations do not silently
compete: a second owner is rejected before it can assign a colliding sequence. Error
mechanisms may differ where the differential test intentionally compares the shared
fail-closed category rather than an identical low-level exception.

## 9. Native Evidence

### Coordinates and toolchain

| Coordinate | Exact value |
|---|---|
| bbagent implementation | `1798dcd0e92a4b68facd8087ea863d8c8aacc3fa` |
| build wrapper/builder | `773c9911af7f043ddd8e995f6ed0d33cc84c5b1c` |
| measurements | `1798dcd0e92a4b68facd8087ea863d8c8aacc3fa` |
| final JVM test run | `773c9911af7f043ddd8e995f6ed0d33cc84c5b1c` |
| bb4t | `f438307280b7a01fd20e99f54cd82682ef15d12a` |
| next.jdbc | `com.github.seancorfield/next.jdbc:1.3.1118` |
| next.jdbc JAR SHA-256 | `387562bfa86dc1a5a402a06a04e8e3b353f82550d296d755e5e7ce4c124275c4` |
| sqlite-jdbc | `org.xerial/sqlite-jdbc:3.53.2.1` |
| sqlite-jdbc JAR SHA-256 | `f55e405ed96d5ffe629e05b7b51b059e1c7d64527c0cc90a972fbac06730ccc1` |
| SQLite runtime | `3.53.2` |
| Leiningen | `2.11.2` |
| Java | `25.0.4+7-LTS` |
| GraalVM distribution | `Oracle GraalVM 25.2.4+7.1` |
| native-image | `25.0.4` |

As in A0, `25.0.4` is the JDK/native-image language version and `25.2.4+7.1` is the
Oracle GraalVM distribution release. Source and direct dependency coordinates are
pinned and the full resolved direct-dependency JARs are digest-gated; this is not a
fully toolchain-pinned reproducible build.

The measurement and native implementation coordinate is the complete adversarially
corrected store/session/test implementation. The final JVM tests ran at the builder
commit, whose only change after that implementation is the exact native source pin in
`script/build-native`.

The native-image phase took 38.6 s; wrapper wall time was 191.61 s; maximum RSS was
3,852,524 KiB; 22 native-image option/configuration warnings excluding feature
environment notices were recorded. Build-log SHA-256 is
`a604cc5f759d7bb997a9a52783bd4d2b5ae7a4ab82abad97d22ea54af1f12714`.

### Final native pass

The relocated distribution created SQLite session `s0b-native-main` in run
`32dd2c18-16e5-4d33-8721-0913b08463f0`, closed at 28 contiguous events, and verified
the required oversized object
`sha256:bca09f4a757d5571c7d9f3341d4301f3c391c090826acc1a3013c6bcb7c01722`.
A new process resumed it in run `ebae5033-6834-49f0-ac67-9692b3c189d8`, reconstructed
the retained value with count 70,000, closed at 49 events, and reverified the same
object. Session listing and all-event EDN inspection passed. The closed database was
200,704 bytes with SHA-256
`85fe1eaced0ed76209120bc3c4a976caa7ed22ed0300661addcf5d0a55e3ba4a`.
Database/inspect sizes, the 49-line inspect count, and complete distribution size are
retained in `build-evidence.txt`.

The wrapper also passed the two hard-exit probes, relocated sidecar loading, absent
and digest-mismatched sidecar failures before database creation, and a check that the
designated runtime temporary directory remained empty. Native evidence proves these
product paths, not complete JVM/native suite parity.

### Preliminary native proof failures

Four unsuccessful wrapper attempts are retained across the S0b history:

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
3. The first second-pass attempt used an incorrectly expanded full bbagent SHA and the
   remote refused that nonexistent source before checkout. Builder `773c9911` corrected
   the pin to `1798dcd0e92a4b68facd8087ea863d8c8aacc3fa`.
4. The next setup resolved and assembled the exact sources but stopped before
   native-image because that shell lacked `GRAALVM_HOME`. The final run explicitly used
   `/home/chuck/.sdkman/candidates/java/current` and passed.

None falsely passed: the product-proof failures terminated at their assertions, and
the setup failures terminated before product gates. The corrected final wrapper passed.

### Artifact hashes

| Artifact/evidence | Bytes | SHA-256 |
|---|---:|---|
| `dist/bbagent` | 65,603,840 | `b88bf4d559fe2d5fabece2899dc73259e65e7c24baad1856b5a386e36ddda93d` |
| `dist/libsqlitejdbc.so` | 1,093,888 | `f374da845a36d0a663521457f8e454413325e3b8247a15c2677426f4b15cf6ac` |
| `dist/THIRD_PARTY_NOTICES.md` | 2,141 | `8d26e1419db5020baedacdf10e2a0464d979a958dd83f429d7e466fc9adb47b5` |
| `s0b-create.edn` | - | `8cf3be9ddef792c87dce2ec05e640b2b5125deee673d1e01b31cba1e565ac0aa` |
| `s0b-resume.edn` | - | `0f14a820633a7b90c07e3eeaa1656eb22a6a1c567c6b4b6de1ef5fa20d0043dd` |
| `s0b-ambiguous-exit.edn` | - | `a55a9c5be58b014c3b26b1b38b3fe56167fea83c0f8d32bb7c93f1671e56f549` |
| `s0b-ambiguous-check.edn` | - | `379eebee6691110c8d66ceac5615c835267477e7661f96cf4c0c2ba1493f25f6` |
| `s0b-transaction-exit.edn` | - | `a96335a9839530ec0b1d29bf3eccb860c5f071f196f67a6689d812870a7f23fb` |
| `s0b-transaction-check.edn` | - | `f129bdff5113785fdd38d204e7e1a6c7a87d18096e4bfde00c56af7978e86fd0` |
| `s0b-authority.edn` | - | `85f2960329e11513db0923b716d7e00cd4639d96258b159718a0b75e8f5e44f2` |
| `missing-sidecar.err` | - | `fe424b320bde7ca3edb402a48a4636970d7ec5aa44624e6d1f00093124893164` |
| `mismatched-sidecar.err` | - | `486ae56f1116a44fd4d9fbd7d47f85b2803db0eb9432bc334a4f89a6e7b80001` |

Executable and sidecar modes were `0755`; complete distribution size was 66,727,025
bytes. The build manifest additionally records native/relocated smoke, session list,
inspect, hard-exit setup, database, notices, and license hashes
(`/tmp/opencode/bbagent-s0b-review2c-20260818/build-evidence.txt`).

## 10. Authority Negatives

SQLite is reachable only from trusted host code. The A0 `:agent/project-read`
ContextSpec and its three grants remain unchanged. The native authority gate passed
four positive probes and 22 negative probes. Every negative returned status `:error`
and category `:bb4t-evaluation-failure`; projected Java class count and supplied import
count were both zero, and no forbidden database was created.

The negative set includes `java.sql.Date`, `java.sql.Timestamp`, connection classes and
`DriverManager`, xerial classes, `next.jdbc`, `bbagent.sqlite`, `bbagent.journal`,
`bbagent.store`, `bbagent.storage`,
`bbagent.sqlite-store`, and S0b smoke implementation entry points
(`src/bbagent/sqlite.clj:205-307`). Tests also assert the unchanged ContextSpec,
effective grants, zero projected classes/imports, common failure category, and absent
forbidden database (`test/bbagent/integration_test.clj:46-68`). No database path,
connection, SQL/JDBC function, store handle, host namespace, or implementation
function is projected into model SCI.

This preserves A0's semantic restriction; it does not add hard process isolation or
hostile-code containment.

## 11. Performance and Storage Measurements

The measurements are local engineering measurements, not benchmarks: one generated
session per size, one sample per size, and append percentiles taken within that
session (`artifacts/s0b-measurements.edn`). File full-read, validation, checkpoint, and
tail measurements after append are explicitly **warm-cache** operations. File reopen
constructs and locks the root store without reading a journal; its resume window then
measures first session recovery plus validation/checkpoint/tail. The 10,000-event
result is:

| Operation | File | SQLite | Interpretation |
|---|---:|---:|---|
| Append median | 0.055 ms | 0.099 ms | File lower in this workload |
| Append p95 | 0.089 ms | 0.129 ms | File lower in this workload |
| Append total | 609.0 ms | 1,051.8 ms | SQLite paid per-transaction cost |
| Warm full read | 0.022 ms | 401.6 ms | File returns its recovered in-memory vector |
| Warm validation | 0.004 ms | 341.5 ms | File cache count versus SQLite O(n) validation |
| Warm latest checkpoint | 0.788 ms | 0.315 ms | SQLite index remained lower in this run |
| Warm ten-event tail | 2.755 ms | 0.540 ms | SQLite cursor query remained lower in this run |
| Root-store reopen | 0.104 ms | 0.725 ms | File session recovery is intentionally lazy |
| First recovery + validate + checkpoint + tail | 516.9 ms | 337.7 ms | Both pay O(n) validation/recovery work |
| CAS put / get | 0.945 / 0.399 ms | 0.211 / 0.102 ms | Local result only |
| Closed storage | 3,164,505 bytes | 7,880,704 bytes | SQLite was 2.49x larger |

SQLite checkpoint lookup was about 2.5x lower and tail lookup about 5.1x lower than the
file backend's warm-cache scans in this particular 10k run. The cold resume window was
about 1.53x lower for SQLite. These ratios describe the recorded local run, not a
portable service-level claim. SQLite streaming validation still decodes, checks, and
hydrates every row and remains O(n); the file backend performs the same integrity and
hydration work when a session is first recovered, then serves repeated queries from
the owned process cache. The measurement does not cover listing many file sessions or
another process while the file root lock is held.

Append medians are not uniformly ordered: file/SQLite were 0.323/0.206 ms at 100
events, 0.104/0.118 ms at 1,000, and 0.055/0.099 ms at 10,000. They vary with local
warmup, filesystem, WAL, and within-session effects and must remain labeled local
engineering measurements.

SQLite closed storage was larger at every measured size: 172,032 versus 99,801 bytes
at 100 events, 864,256 versus 373,503 at 1,000, and 7,880,704 versus 3,164,505 at
10,000. Live SQLite storage was larger again because the measurement included live
WAL-related files: 11,951,872 bytes at 10,000 versus the 7,880,704-byte closed size.

The cumulative-checkpoint workload stored 1,000 messages, a complete checkpoint every
100 messages, and 1,010 total events. On the already-owned stores, cached file
latest-checkpoint lookup was 0.185 ms and validation 0.005 ms, while SQLite measured
7.63 ms and 65.64 ms. Those are warm-cache versus database-read figures, not recovery
comparisons. Closed SQLite storage was 1,224,704 bytes versus 472,771. The SQLite index
finds the row quickly; it does not make an increasingly large checkpoint cheap to
decode, validate, or store.

## 12. Remaining Long-Session Debt

S0b improves physical selection without changing A0 checkpoint semantics. Every
checkpoint still repeats all current provider-neutral messages and all replay forms
(`src/bbagent/session.clj:58-63`). Resume first performs complete-history validation,
then selects the latest checkpoint and tail (`src/bbagent/session.clj:181-205`).
Consequently:

- each resume still opens a new store and performs O(n) validation/recovery that
  hydrates referenced objects; file queries then retain the complete hydrated session
  cache for that store's lifetime;
- checkpoint payload size grows with the complete retained conversation and replay
  program;
- repeated cumulative checkpoints consume superlinear total bytes over a long
  session even though lookup is indexed;
- recovery replays every retained form and still reconstructs SCI state rather than
  persisting a heap;
- foreign tagged literals are opaque to secret stripping, large-string
  externalization, and CAS hydration, and are not accepted from the session/model
  path today;
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
persistent cold-process scan cost without relying on a process-local cache. Keep file
support as the accepted, human-readable, single-owner reference and for sessions
already stored there. Require explicit backend selection for existing sessions until a
separately designed migration exists. The code at this finding still defaults to file;
S0b itself does not change that default.

Do not represent this recommendation as implementing any of the following:

- file/SQLite import, automatic migration, downgrade, or merged session discovery;
- a general schema migration registry beyond transactional version-1 creation;
- complete crash, power-loss, or network-filesystem proof, or SQLite writer-throughput
  and fairness results; the file backend explicitly rejects a concurrent root owner;
- complete JVM/native test-suite parity or fully toolchain-pinned reproducibility;
- a BuildManifest or durable executable/sidecar/toolchain provenance coordinate;
- bounded-size or incremental checkpoints, object GC, archival, replication, or
  backup policy;
- model-visible SQL/JDBC, database paths, trusted storage namespaces, or host handles;
- FTS, vectors, general memory, project editing, process/network authority, additional
  storage systems, or encryption;
- TUI, prompt/model-loop redesign, multi-agent behavior, A1, or broad BB2 work.

Stop here for S0b findings and fresh review. Do not begin A1 as part of this decision.

## 14. Closure Note

This section is appended at S0 closure. It records a later decision and changes no
measurement, verdict, coordinate, or evidence above.

The fresh review section 13 required was performed and accepted. The recommendation
to switch **new sessions** to SQLite is implemented after this findings commit, so
section 1's statement that "the implementation's current default remains `file`" and
section 13's "the code at this finding still defaults to file" describe the code at
the time of measurement, not the current default. The current default, the retained
file-backend policy, and the frozen S0a/S0b coordinates are recorded in
`docs/S0_CLOSURE.md`.

The accepted change is limited to backend selection defaults. No storage semantics,
schema, durability policy, transaction boundary, CAS behaviour, recovery rule, or
authority result recorded above was altered, and the long-session debt in section 12
remains open and unaddressed.
