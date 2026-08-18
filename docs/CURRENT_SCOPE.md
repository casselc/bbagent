# Current Scope: S0b

S0b asks whether SQLite can implement the accepted A0 durable event journal and
content-addressed object semantics more simply and scalably without weakening
durability, recovery, provenance, or the bounded SCI authority proven by S0a.

Owned work is limited to a small file/SQLite store contract, one local database per
application state root, canonical event and object persistence, conservative SQLite
durability policy, transactional schema versioning, differential recovery tests,
explicit backend selection, native create/resume evidence, measurements, and findings.

The EDN-lines journal remains the reference backend. S0b does not redesign agent
memory, prompts, the model loop, or the TUI; expose SQL, JDBC, database paths, or
trusted storage namespaces to model SCI; add FTS, vectors, editing, processes,
multi-agent behavior, replication, or other storage systems; begin A1 or BB2 broadly;
or change Track A. Stop after findings and fresh review.
