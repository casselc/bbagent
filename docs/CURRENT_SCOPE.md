# Current Scope: S0a

S0a asks whether the custom bb4t + bbagent native application can reliably use
JNI-backed SQLite from trusted host code while the accepted A0 bounded SCI Context
retains exactly its existing authority.

Owned work is limited to application-scoped `next.jdbc` and `sqlite-jdbc`
dependencies, one file-backed commit/reopen/rollback smoke operation, exported native
library packaging, JVM/native evidence, model-authority negative probes, measurements,
licensing notices, and findings.

S0a does not replace or modify the EDN-lines journal, design storage abstractions,
migrate sessions, add FTS/vector search, expose SQL or JDBC to agents, add project
search/edit/process authority, build the TUI, introduce other storage/runtime systems,
begin BB2 broadly, or change Track A. Stop after findings and review.
