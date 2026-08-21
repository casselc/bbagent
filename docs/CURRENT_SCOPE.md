# Current Scope: A1.1

**Milestone status:** A0 PASS, S0a PASS, S0b PASS, A1 PASS (accepted with
follow-ups), A1.1 PASS, reviewed, accepted, and frozen at `bbagent-a1.1`.

A1.1 findings are in `docs/A1_1_FINDINGS.md`, with the review recorded in its
section 10. The review upheld the verdict, fixed three defects, and corrected
three claims. New sessions now default to `:grounded`; a resumed session keeps
the orientation it was started with unless that run overrides it.

A2 is not open. Before it begins, one entry condition carried out of the A1.1
review must be satisfied: **absent authority is asserted as a hardcoded constant
in three places** — the base system prompt, the generated preamble, and the
grounding constraint — and all three become false the moment `project/list`
exists. They must derive from the effects the context actually grants. See
`docs/A1_1_FINDINGS.md` section 9.

A1 is complete and frozen at `bbagent-a1`. Its findings are in
`docs/A1_FINDINGS.md`.

## Frozen coordinates

| Milestone | Repository | Tag |
|---|---|---|
| A1 | bbagent | `bbagent-a1` |
| A1.1 | bbagent | `bbagent-a1.1` |
| A1 / A1.1 | bb4t | `bb4t-s0a`, unchanged |

A1.1 required no bb4t change, as its scope predicted. bb4t's last A1.1-relevant
coordinate is the S0a application build profile plus the A1 `charm.clj`
dependency; no new bb4t tag exists because no bb4t source changed.

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
