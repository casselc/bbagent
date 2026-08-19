# S0 Closure

S0a and S0b are accepted and frozen. This document records the integration result,
the immutable coordinates, and the two policy decisions the accepted reviews
authorized. It does not restate or revise the findings, which remain the evidence of
record in `docs/S0A_SQLITE_NATIVE_FINDINGS.md` and
`docs/S0B_SQLITE_STORAGE_FINDINGS.md`.

## 1. Integration

Both integrations were history-preserving fast-forwards. No measured implementation
or evidence commit was squashed, rebased, reworded, or dropped, and the feature
branches are retained.

| Repository | Branch | Integrated from | Commits | Method |
|---|---|---|---|---|
| `casselc/babashka` (bb4t) | `bb4t/dev` | `bb4t/sqlite-spike` | 3 | fast-forward |
| `casselc/bbagent` | `main` | `s0/sqlite-store` | 19 | fast-forward |

## 2. Milestone tags

| Tag | Repository | Commit | Kind |
|---|---|---|---|
| `bb4t-s0a` | bb4t | `93f15faafce9e1411b60c60fff6d4865a2b17a2a` | annotated |
| `bbagent-s0b` | bbagent | `04d80c9e45c09e83cf79139289acba48e659f61b` | annotated |

These follow the established convention that a milestone tag names the accepted
branch tip **including** its evidence commit, while the findings record the distinct
implementation coordinate. The pre-existing `bbagent-s0a` tag is lightweight rather
than annotated; it is left unmoved because it is immutable, and the inconsistency is
recorded here rather than repaired by rewriting a frozen coordinate.

## 3. Frozen coordinates

Implementation coordinates are distinct from the later evidence-only commits that
record their measurements. Both are listed so neither is mistaken for the other.

| Coordinate | Exact value |
|---|---|
| bb4t custom-image source (S0a implementation) | `f438307280b7a01fd20e99f54cd82682ef15d12a` |
| bb4t S0a evidence commit / `bb4t-s0a` | `93f15faafce9e1411b60c60fff6d4865a2b17a2a` |
| bb4t application profile | `:app/bbagent` |
| bbagent S0a implementation | `45a63073ad02646bb99b2eb9d182060c7473b432` |
| bbagent S0a build wrapper | `f979f96449ccb648a737832125a93b5616399014` |
| bbagent S0a evidence commit / `bbagent-s0a` | `d597169fa8af84c3a74e78605a07c20caff01dc1` |
| bbagent S0b implementation | `1798dcd0e92a4b68facd8087ea863d8c8aacc3fa` |
| bbagent S0b builder / final JVM test run | `773c9911af7f043ddd8e995f6ed0d33cc84c5b1c` |
| bbagent S0b evidence commit / `bbagent-s0b` | `04d80c9e45c09e83cf79139289acba48e659f61b` |
| next.jdbc | `com.github.seancorfield/next.jdbc 1.3.1118` |
| next.jdbc JAR SHA-256 | `387562bfa86dc1a5a402a06a04e8e3b353f82550d296d755e5e7ce4c124275c4` |
| sqlite-jdbc | `org.xerial/sqlite-jdbc 3.53.2.1` |
| sqlite-jdbc JAR SHA-256 | `f55e405ed96d5ffe629e05b7b51b059e1c7d64527c0cc90a972fbac06730ccc1` |
| SQLite runtime version | `3.53.2` |
| sqlite sidecar digest | `f374da845a36d0a663521457f8e454413325e3b8247a15c2677426f4b15cf6ac` |
| S0b `dist/bbagent` digest | `b88bf4d559fe2d5fabece2899dc73259e65e7c24baad1856b5a386e36ddda93d` |
| GraalVM distribution / native-image | Oracle GraalVM `25.2.4+7.1` / `25.0.4` |

The sqlite sidecar digest is the runtime integrity gate enforced before any SQLite
connection or database creation. It remains an expected-dependency check, not a
durable executable-provenance coordinate; that is still deferred to BB2.

## 4. Accepted policy: SQLite is the default for new sessions

The S0b recommendation to switch new sessions to SQLite after fresh review is
accepted and implemented.

- `bbagent.storage/backend` maps an unspecified backend to `:sqlite`.
- `bbagent.session/start!` defaults `:store-backend` to `:sqlite`.
- `bbagent.session/resume!` defaults `:store-backend` to `:sqlite`, matching
  `start!`.
- `--store file` and `--store sqlite` remain explicit on `run`, `resume`,
  `sessions`, and `inspect`; `:store-backend :file` and `:store-backend :sqlite`
  remain the programmatic equivalents.

## 5. Accepted policy: existing sessions keep their backend identity

The file backend remains supported, human-readable, the reference semantics, useful
for debugging and EDN export, and the backend for sessions already stored there.

Backend selection is **never** inferred, probed, or migrated:

- a newly created session uses SQLite unless `file` is selected explicitly;
- an existing file session must be opened explicitly with `file`;
- an existing SQLite session is opened by the default, or explicitly with `sqlite`.

Opening a session with the wrong backend fails closed as `:session-recovery-failure`.
It does not reinterpret, import, convert, or damage the other backend's durable
state. `test/bbagent/agent_test.clj` `existing-session-backend-identity-test` proves
this directly: after creating a file session, the default backend refuses to resume
it, the file session's events validate intact, the SQLite store lists no sessions,
and explicit `file` selection resumes it.

There is no automatic file-to-SQLite migration, no SQLite-to-file downgrade, no
import, and no merged cross-backend session discovery. A migration mechanism, if it
is ever wanted, requires its own design and review.

## 6. What closure does not claim

Closure integrates and freezes accepted work. It does not add any claim beyond the
findings: no long-session checkpoint redesign, no object reachability or GC, no
bounded validation or recovery, no BuildManifest or durable executable provenance, no
cross-platform or static-linking result, no writer-throughput or fairness result, and
no complete JVM/native suite parity. The remaining long-session storage debt recorded
in `docs/S0B_SQLITE_STORAGE_FINDINGS.md` section 12 is unchanged and is explicitly not
addressed by the default-backend change.
