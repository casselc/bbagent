# Current Scope: A1

**Milestone status:** A0 PASS, S0a PASS, S0b PASS, A1 ACTIVE.

A1 asks whether a native interactive TUI can become the preferred human/operator
interface to bbagent while remaining only a projection and controller over the
existing `AgentSession`, store, and bb4t semantic state, without inventing a second
state model or a second authority path.

## Owned Work

A thin interactive client in bbagent: a bounded TUI runtime spike with a native
proof and an ADR; transient view state kept separate from durable application state;
a small command/event seam so model work does not block terminal redraw; header,
conversation, input, operator REPL, context/capability inspector, and incremental
event panes; a small session browser over `list-sessions`; structured error
presentation and structured event drill-down; a discoverable keymap and documented
interrupt semantics; native evidence including the TUI and the SQLite sidecar; an
extended authority regression corpus covering the TUI implementation classes and
namespaces; deterministic view-model, reducer, incremental-event, agent-integration,
and resume tests; a dogfood session and findings.

The TUI reaches semantic state only through `bbagent.session`, `bbagent.agent`,
`bbagent.storage`, `bbagent.store`, and the public bb4t facade. It does not call bb4t
kernel internals, bypass `AgentSession` for model turns, depend on
`bbagent.sqlite-store` internals, JDBC, or SQL schema details, or acquire generic
filesystem, process, or database authority and re-expose it through UI callbacks. The
operator REPL attaches to the actual bounded session Context; a trusted host REPL is
not introduced. Durable checkpoint semantics are unchanged; A1 may exploit the indexed
`latest-checkpoint`, `events-after`, and request lookups for UI efficiency only.

S0 closure is complete and recorded in `docs/S0_CLOSURE.md`. New sessions default to
SQLite; existing sessions keep their backend identity and are never inferred,
converted, or migrated.

## Explicit Exclusions

No project listing, search, grep, editing, or test capability; no shell, process, or
Git mutation; no SmolVM, pods, or hard worker isolation; no memory, embeddings,
vector search, SQLite FTS, or session full-text search; no work/issues, skills, or
model routing; no planner, reviewer, subagents, or multi-agent console; no MCP, A2A,
ACP, web UI, or HTTP server; no generalized BB2 BuildManifest or build-profile
framework; no storage migration, import, object GC, checkpoint redesign, archival, or
retention; no Track A changes. The bundled SQLite having FTS5 is not permission to add
search. Milestone evidence commands stay out of the TUI.

## Stop Gate

Stop after A1 findings and fresh review. Do not begin the next milestone
automatically; recommend it from dogfood evidence.
