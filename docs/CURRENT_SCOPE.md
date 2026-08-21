# Current Scope: A2

**Milestone status:** A0 PASS, S0a PASS, S0b PASS, A1 PASS, A1.1 PASS
(frozen at `bbagent-a1.1`), A2 ACTIVE.

## A2: useful semantic project world

A2 asks whether the model can do real project work through a small composable
semantic capability set while keeping persistent SCI as its working interface.

A1.1 produced the entry signal: the model discovers its surface and correctly
reports that directory enumeration is missing. `project/list` is the answer to
that specific report.

### Delivered

- **`project/list`** (bb4t): one directory deep, sorted inert data, symbolic
  links described but never followed, bounded by `:project/list-max-entries`.
- **`:agent/project-survey`**: a new profile carrying the A0 authority plus
  listing. `:agent/project-read` is frozen and still reproduces the recorded
  A0/A1/A1.1 surface exactly, which is pinned by test.
- **Profile selection in bbagent**: `session/start!` takes `:profile` and
  defaults to the survey profile; `resume!` inherits the profile recorded in
  the session's start coordinate, so an A0-era session is never resumed into a
  wider surface where a replayed form that once failed would now succeed.
- **`:derived` orientation is now the default**, replacing `:grounded`. This
  was forced rather than chosen: `:grounded` states limits as prose, including
  "you cannot enumerate a directory", which became false the moment
  `project/list` was granted. `:derived` generates its claims from the surface,
  so it picked up the new operation with no prompt edit and denies nothing the
  context grants.

### Evidence status

- deterministic suite: 120 tests, 882 assertions, 0 failures;
- authority boundary tests for `project/list` cover root listing, non-recursion,
  absolute paths, `..` traversal, symlink refusal in both directions,
  non-directories, absent paths, malformed arguments, and the grant itself;
- **not yet done:** native build and PTY evidence for the new capability; a live
  dogfood re-run of the A1.1 prompt against the widened surface. The A1.1
  comparison harness now interleaves and rotates variants and includes
  `:derived`, so the re-run is ready when an endpoint is.

### Still open in A2

`project/search`, `project/edit` with version-anchored mutation, and
`project/test` are not implemented. Neither is any shell, process, or Git
capability. The measurement target also needs revisiting: the harness's
`concludes-limitation?` scored the right answer while enumeration was missing,
and now scores the wrong thing.

`resources/bbagent/system.txt` still carries one enumerated-absence sentence.
It remains true -- no editing, shell, process, or network authority exists --
and it is the digest anchor for the recorded A1.1 prompt coordinates, so it
changes when a capability actually falsifies it.

---

## A1.1 (closed, frozen at `bbagent-a1.1`)

## A1.1: capability orientation

A1.1 asks one bounded question:

> Can the model reliably discover and use its currently granted semantic
> surface if bbagent explicitly orients it to `apropos`/`doc` and supplies a
> short capability preamble derived from the actual Context description?

The A1 dogfood is the motivation. Asked what files a project contains, the model
spent eleven of twelve actions guessing nonexistent Vars and exhausted the
action budget, while the operator watched the correct three-Var capability
surface on screen. It never tried `(apropos "")` or `(doc project/read)`, both
of which already work in the bounded Context. That is a failure to orient to
existing authority, not evidence that more authority is needed.

### Owned work

Compare at least three variants against the exact prompt that failed
(*"What files does this project contain, and what does each one do?"*):

```text
baseline              the current system prompt
minimal orientation   one instruction naming apropos and doc
generated orientation a short preamble derived from the Context description
                      and RuntimeCatalog, plus the apropos/doc instruction
grounded orientation  generated, plus a constraint on what may be asserted
```

The fourth variant was not planned. It was added after the first three showed
that listing operations fixes discovery without preventing unsupported claims;
see the findings.

The preamble must be **another projection of the same authority description**
that already feeds the TUI capability pane and `apropos`/`doc`, not parallel
handwritten prose that would need maintaining as capabilities change. Keep it
short; do not dump the catalog into every turn.

A successful outcome includes the model concluding *"I cannot list files with my
present capabilities; give me filenames to read"* — reaching that quickly and
correctly is the result we want, because it distinguishes *cannot discover
capability* from *discovers capability and finds it insufficient*. Only the
second is evidence for adding `project/list`.

### Explicit exclusions

Do not combine anything else with this change, or the comparison cannot
attribute the improvement. Specifically **not** in A1.1:

- `project/list` or any other new capability, and no change to the authority
  surface, ContextSpec, grants, or RuntimeCatalog;
- unknown-Var retry or action-budget policy;
- streaming or incremental provider output;
- the recorded A1 UI observations: event-column truncation and `:bb4t/event`
  diagnostics crowding the event pane;
- TUI runtime replacement, storage or checkpoint redesign, memory, search, or
  multi-agent work.

### Stop gate

Stop after the variant comparison and findings. If the model discovers its
surface and correctly reports that directory enumeration is missing, that is
strong evidence for A2 beginning with `project/list`.

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
