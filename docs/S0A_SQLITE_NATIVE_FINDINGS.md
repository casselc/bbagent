# S0a SQLite Custom Native-Image Findings

## 1. Verdict

**Pass for the bounded S0a question.** The source-pinned Linux x86_64 custom bb4t +
bbagent native application includes current `next.jdbc` and xerial sqlite-jdbc,
loads an exported sqlitejdbc JNI sidecar after relocation, completes file-backed
commit/reopen/rollback work, and constructs the unchanged A0 bounded Context without
projecting JDBC, SQLite, next.jdbc, or trusted bbagent SQLite authority.

This is not approval to replace the journal. It is one platform/toolchain packaging
result, not a release, cross-platform, static-linking, general BuildManifest, or
storage-design result.

## 2. Frozen And Measured Coordinates

Immutable pre-spike tags were created before implementation:

```text
bb4t-a0     896af34a7933a80f9fec16995d7a477354b49649
bbagent-a0  e5b11b9f46db767a7a3e4ba18e1528a3ec4eec02
```

Final S0a build coordinates:

```text
build wrapper       f979f96449ccb648a737832125a93b5616399014
bb4t source         f438307280b7a01fd20e99f54cd82682ef15d12a
bbagent source      45a63073ad02646bb99b2eb9d182060c7473b432
application profile :app/bbagent
target              Linux x86_64, glibc
```

The wrapper clones and verifies the two implementation SHAs, initializes the pinned
bb4t submodules, and embeds the implementation coordinates. The wrapper commit is
recorded separately because it stages and tests the distribution but is intentionally
not the bbagent implementation embedded in the image.

## 3. SQLite And next.jdbc Coordinates

```text
next.jdbc Maven      com.github.seancorfield/next.jdbc 1.3.1118
next.jdbc tag        v1.3.1118
next.jdbc commit     1b2e6cf0a8b042f8bcc826edf2e60e6a43365a72
next.jdbc JAR SHA    387562bfa86dc1a5a402a06a04e8e3b353f82550d296d755e5e7ce4c124275c4
next.jdbc license    Eclipse Public License 2.0

sqlite-jdbc Maven    org.xerial/sqlite-jdbc 3.53.2.1
sqlite-jdbc tag      3.53.2.1
sqlite-jdbc commit   94c0ea142dccd729c9a56833704743d74602e58c
sqlite-jdbc JAR SHA  f55e405ed96d5ffe629e05b7b51b059e1c7d64527c0cc90a972fbac06730ccc1
JDBC driver version  3.53.2.1
bundled SQLite       3.53.2
sqlite-jdbc license  Apache License 2.0 plus NOTICE/LICENSE.zentus
```

The versions are fixed, not moving coordinates. Upstream documents GraalVM
native-image support since `3.40.1.0`, split platform-native artifacts since
`3.53.0.0`, and recommends `org.sqlite.lib.exportPath` for sidecar packaging.

## 4. Dependency And Build Changes

Only bb4t's inactive-by-default `:app/bbagent` profile gained the two dependencies.
The ordinary bb4t dependency tree does not contain them. The old broader
`:feature/jdbc` and `:feature/sqlite` profiles remain unchanged and disabled.

The app profile also sets `Multi-Release: true`. This is required because xerial's
`SqliteJdbcFeature` is a Java 9 multi-release class. Lein retained the versioned class
and native-image properties but initially omitted that manifest attribute, causing a
useful fail-closed `SqliteJdbcFeature class not found` build failure.

No bb4t runtime, context, catalog, kernel, semantic operation, or SCI projection code
changed. No bbagent journal, session, provider, model action, or existing ContextSpec
code changed.

## 5. Native Loading Strategy

The accepted distribution uses xerial's build-time export mode:

```text
-Dorg.sqlite.lib.exportPath=<absolute dist directory>
```

The resulting bundle contains:

```text
dist/bbagent
dist/libsqlitejdbc.so
dist/THIRD_PARTY_NOTICES.md
dist/licenses/next.jdbc-EPL-2.0.txt
dist/licenses/sqlite-jdbc-APACHE-2.0.txt
dist/licenses/sqlite-jdbc-LICENSE.zentus
```

The wrapper verifies the sidecar digest against the exact Linux x86_64 resource from
the pinned sqlite-jdbc artifact, sets executable/sidecar modes to `0755`, relocates the
bundle, runs from a different working directory with a dedicated empty `TMPDIR`, and
requires that directory to remain empty. Removing only the sidecar produces exit `1`
and `UnsatisfiedLinkError`; it does not create a database. Restoring the sidecar
restores operation.

The original S0a wrapper required no operator-supplied runtime extraction,
`org.sqlite.lib.path`, `java.library.path` override, or other undocumented loading
variable. Fully static SQLite linkage was not tried. The current S0b follow-up below
sets sqlite-jdbc's path/name internally after verifying the adjacent sidecar.

### Current deployment contract

The S0b second-pass hardening makes the previously implicit loading rule explicit and
enforces it before any SQLite connection or database creation. The supported artifact
is the complete Linux x86_64 glibc `dist/` bundle: exact, unrenamed
`libsqlitejdbc.so` must remain mode `0755` beside the canonical `bbagent` executable.
The process working directory, `TMPDIR`, and the build-time absolute export path do not
select the runtime library. At native runtime bbagent resolves the canonical executable,
requires the adjacent sidecar to be a regular executable file, verifies SHA-256
`f374da845a36d0a663521457f8e454413325e3b8247a15c2677426f4b15cf6ac`, and sets
sqlite-jdbc's path/name properties to that verified file before driver loading.
Missing, non-executable, or digest-mismatched sidecars fail as
`:journal-storage-failure` before a database is created. This is an integrity check,
not protection against a local writer racing replacement in a writable deployment
directory.

## 6. JNI And GraalVM Configuration

No hand-written reflection or JNI JSON was added. The assembled uberjar preserves:

```text
META-INF/native-image/org.xerial/sqlite-jdbc/native-image.properties
META-INF/versions/9/org/sqlite/nativeimage/SqliteJdbcFeature.class
META-INF/native-image/io.github.seancorfield/next.jdbc/native-image.properties
META-INF/services/java.sql.Driver
org/sqlite/native/Linux/x86_64/libsqlitejdbc.so
```

xerial contributes `SqliteJdbcFeature`, which selects/exports the target library and
registers required JNI access. next.jdbc contributes build-time initialization for
its Clojure namespaces and `java.sql.SQLException`. bb4t already enables JNI and native
access. Native analysis reported `75` types, `79` fields, and `72` methods registered
for JNI access and listed `org.sqlite.nativeimage.SqliteJdbcFeature` as an active
feature.

GraalVM warns that xerial's `--enable-url-protocols=jar` option is deprecated. It still
works on this toolchain and is upstream-owned compatibility debt.

## 7. Trusted SQLite Smoke

`bbagent.sqlite` uses next.jdbc rather than custom JDBC plumbing. It:

1. opens a new explicit file database;
2. records driver and SQLite versions and compile options;
3. creates `s0a_smoke`;
4. inserts row 1 inside a committing transaction;
5. closes and reopens the connection;
6. verifies row 1;
7. inserts row 2 in a rollback-only transaction;
8. verifies both rows are visible before rollback;
9. closes and reopens again;
10. verifies only row 1 persists.

The native database is 8,192 bytes and contains only `{:id 1 :value "committed"}`.
JDBC objects remain inside trusted host code; returned evidence is inert EDN.

Observed compile options for the pinned Linux x86_64 sidecar:

```text
ATOMIC_INTRINSICS=0, COMPILER=gcc-4.1.2 20080704 (Red Hat 4.1.2-55),
DEFAULT_AUTOVACUUM, DEFAULT_CACHE_SIZE=-2000, DEFAULT_FILE_FORMAT=4,
DEFAULT_FILE_PERMISSIONS=0666, DEFAULT_JOURNAL_SIZE_LIMIT=-1,
DEFAULT_MEMSTATUS=0, DEFAULT_MMAP_SIZE=0, DEFAULT_PAGE_SIZE=4096,
DEFAULT_PCACHE_INITSZ=20, DEFAULT_RECURSIVE_TRIGGERS,
DEFAULT_SECTOR_SIZE=4096, DEFAULT_SYNCHRONOUS=2,
DEFAULT_WAL_AUTOCHECKPOINT=1000, DEFAULT_WAL_SYNCHRONOUS=2,
DEFAULT_WORKER_THREADS=0, DIRECT_OVERFLOW_READ,
DISABLE_PAGECACHE_OVERFLOW_STATS, ENABLE_COLUMN_METADATA, ENABLE_DBSTAT_VTAB,
ENABLE_FTS3, ENABLE_FTS3_PARENTHESIS, ENABLE_FTS5, ENABLE_LOAD_EXTENSION,
ENABLE_MATH_FUNCTIONS, ENABLE_PERCENTILE, ENABLE_RTREE, ENABLE_STAT4,
ENABLE_UPDATE_DELETE_LIMIT, HAVE_ISNAN, JDBC_EXTENSIONS, MALLOC_SOFT_LIMIT=1024,
MAX_ATTACHED=125, MAX_COLUMN=32767, MAX_COMPOUND_SELECT=500,
MAX_DEFAULT_PAGE_SIZE=8192, MAX_EXPR_DEPTH=1000, MAX_FUNCTION_ARG=127,
MAX_LENGTH=2147483647, MAX_LIKE_PATTERN_LENGTH=50000,
MAX_MMAP_SIZE=1099511627776, MAX_PAGE_COUNT=4294967294, MAX_PAGE_SIZE=65536,
MAX_SQL_LENGTH=1073741824, MAX_TRIGGER_DEPTH=1000, MAX_VARIABLE_NUMBER=250000,
MAX_VDBE_OP=250000000, MAX_WORKER_THREADS=8, MUTEX_PTHREADS, SYSTEM_MALLOC,
TEMP_STORE=1, THREADSAFE=1
```

FTS and extension support are properties of the bundled trusted SQLite library. S0a
does not use or expose them.

## 8. JVM And Native Evidence

The exact final source checkouts passed:

```text
bbagent JVM        28 tests, 93 assertions, 0 failures, 0 errors
BB1 focused JVM    18 tests, 164 assertions, 0 failures, 0 errors
```

The native producer and relocated-consumer smokes both report SQLite `3.53.2`, driver
`3.53.2.1`, successful commit/reopen, visibility before rollback, rollback absence,
and the complete authority gate. The wrapper writes hashes, modes, coordinates,
producer/relocated smoke outputs, and missing-sidecar exit status under the build root.
Exact build, test, startup, and artifact log hashes are retained in
`artifacts/s0a-evidence.edn`; the corresponding raw logs remain under the measured
build root.

Two preliminary attempts are retained as findings:

- one stopped before native-image because `GRAALVM_HOME` was not exported;
- one reached native-image and failed because the uberjar lacked `Multi-Release: true`.

Neither preliminary attempt produced a falsely accepted artifact.

## 9. Model Authority

The actual A0 Context still has exactly:

```text
profile                   :agent/project-read
effective grants          data/json-read, data/json-write, project/read
projected classes         0
supplied imports           0
projected namespaces/Vars  3 / 5 including the base surface
```

Core arithmetic, JSON read/write, and project/read remain successful. Native and JVM
probes require categorized `:bb4t-evaluation-failure` for:

```text
java.sql.DriverManager
java.sql.Connection
java.sql.Statement
java.sql.ResultSet
java.sql.DriverManager/getConnection with an arbitrary path
org.sqlite.JDBC
org.sqlite.SQLiteConnection
next.jdbc require/get-datasource
bbagent.sqlite require/database-smoke!
```

The arbitrary-path probes create no file. There is no SQL semantic operation, raw
JDBC operation, database path binding, or SQLite capability in the catalog. The smoke
CLI itself has trusted operator filesystem authority and is not a model surface.

## 10. Measurements

All figures are local and not controlled performance claims.

```text
                                      A0             S0a
executable bytes                 50,989,312      64,293,120
executable SHA-256               0f1b1d...644     d47595...f96
sqlite sidecar bytes                      -       1,093,888
executable + sidecar bytes       50,989,312      65,387,008
complete dist bytes                       -      65,416,305
native-image phase                   33.7 s          40.4 s
clean wrapper wall                       -          72.12 s
startup median, 30 samples          4.700 ms         5.122 ms
startup p95, 30 samples             5.230 ms         6.017 ms
```

S0a native SQLite open measurements from the producer smoke:

```text
first open        5.767 ms
warm reopen       0.055 ms
verification      0.038 ms
```

The executable grew by 13,303,808 bytes; executable plus sidecar grew by 14,397,696
bytes. The measurements include more than SQLite code alone and must not be treated as
isolated library cost.

Artifact identities:

```text
bbagent SHA-256   d475952421709e95d64282937f7fe77ba0ed03a0ddf371906d35f24bfaf58f96
sidecar SHA-256   f374da845a36d0a663521457f8e454413325e3b8247a15c2677426f4b15cf6ac
sidecar bytes     1,093,888
sidecar mode      0755
```

## 11. Toolchain, Licensing, And Manifest Semantics

```text
OS/kernel          Ubuntu, Linux 7.0.0-29-generic
architecture       x86_64
glibc              2.43
Leiningen          2.11.2
Java               25.0.4+7-LTS
GraalVM            Oracle GraalVM 25.2.4+7.1
native-image       25.0.4
C compiler         GCC 15.2.0
machine target     compatibility
```

The bundle contains xerial's Apache 2.0 and Zentus texts, next.jdbc's EPL 2.0 text
fetched from its exact commit and digest-checked, and the direct-dependency notice.
This is not a full release-wide legal audit of all 69 embedded SBOM components.

BB1 RuntimeManifest remains unchanged in meaning and still describes the accepted
BB1 source/catalog model, not complete application physical reachability. S0a records
the app profile, dependencies, toolchain, executable, and sidecar separately. BB2 may
later define a BuildManifest and reachability coordinate; S0a does not pretend that
work is complete. Durable session coordinates still omit executable and deployed
sidecar digests, application profile, toolchain identity, and physical native
reachability. A1 inspection must not present RuntimeManifest as complete physical
build provenance.

## 12. Remaining Limits And S0b Recommendation

- Linux glibc x86_64 only; no macOS, Windows, arm64, musl, or cross-build result.
- The wrapper source-pins repositories and direct dependency versions but not every
  artifact byte, toolchain package, OS image, or transitive license.
- The trusted spike command accepts an operator-selected path and has a check/open
  race; it is evidence machinery, not a proposed storage API.
- The dependency-augmented native image ran targeted A0 authority probes, not the full
  96-cell BB1 native corpus.
- Native-image reports upstream/dependency deprecation warnings that are not S0a
  correctness failures.
- No crash consistency, concurrency, migration, journal, schema evolution, backup,
  corruption recovery, FTS, vector, or agent SQL design is established.

Proceed to a separately scoped S0b design only if SQLite is selected for durable
storage. S0b should begin from current journal guarantees and define store semantics,
crash/recovery tests, migrations, and compatibility before changing persisted data.
Do not replace the EDN-lines journal as an automatic consequence of this Pass.
