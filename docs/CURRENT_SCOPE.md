# Current Scope: A3a

**For review.** `docs/A3A_FINDINGS.md` section 0 is the reviewer's entry point:
verdict, the boundary, evidence table, and the decisions it needs. This document
states scope; that one states results.

**Milestone status:** A0 PASS, S0a PASS, S0b PASS, A1 PASS, A1.1 PASS
(frozen at `bbagent-a1.1`), **A2 PASS (accepted, frozen at `bbagent-a2` /
`bb4t-a2`)**, A3a complete, recommended PASS.

## A3a: isolated project execution

A3a asks whether trusted host code can execute arbitrary project-owned code in
a hard-bounded worker, get a structured result, terminate it reliably, and prove
the execution cannot reach the authoritative checkout, host secrets, or the
network.

It is a substrate milestone. **The model gains no new authority**: the bounded
Context grants exactly what A2 froze, and the authority negative corpus grew
from 35 probes to 51 to prove the new reachability is not a new authority path.

### Delivered

- **`bbagent.process`**: bounded host subprocess execution — a deadline, output
  budgets, and a process tree that is actually dead when the call returns. The
  unbounded `ProcessBuilder` that read git coordinates at session start now goes
  through it, so that call has a deadline for the first time.
- **`bbagent.snapshot`**: the project input manifest and its coordinate. Sorted
  by path, exclusions recorded rather than applied silently, symbolic links
  described and never followed, and a link out of the tree refused.
- **`bbagent.worker`**: the one place that knows a machine manager exists. One
  ephemeral machine per execution, the project mounted read-only, an overlay
  whose upper layer lives and dies inside the machine, and an environment that
  is constructed rather than filtered.
- **Native evidence**: 13 isolation and bounds gates, 6 lifecycle gates, and 4
  dogfood gates, all run from the image by `script/build-native`.
- **A dogfood that is a real check**: `script/a3a-source-check.clj` verifies
  namespace/path agreement and that every test namespace is registered in the
  runner — an invariant this repository violated while A3a was being written.

### Two measured results that changed the design

- killing the machine manager process is **not** cleanup: it orphans the
  machine, which keeps running. The process primitive destroys descendants
  first, which is what actually ends the workload.
- the overlay's lower layer is **live**, not a frozen copy, so a run whose
  project moved under it reports no input coordinate rather than one naming a
  state it only half saw.

### Evidence status

- deterministic suites: bbagent 181 tests / 1275 assertions, bb4t 25 tests /
  198 assertions, 0 failures;
- native image built with **no new reachability metadata, build flag, or
  dependency** — `bbagent.coordinates` already compiled a `ProcessBuilder` into
  the image;
- authority in the image: 51 negatives denied, `:projected-class-count 0`,
  `:supplied-import-count 0`, and every A2 gate still passing;
- dogfood against the real bbagent checkout: 102 entries, a real babashka check
  passing in 996ms, and a workload that believed it deleted `src/bbagent`
  leaving the checkout byte-identical.

### Explicit exclusions

No model-visible `project/run`, `project/test`, or `project/build`; no generic
shell capability; no background processes or dev-server lifecycle; no Git
tooling; no network enablement; no package-installation policy; no pods as the
execution path; no worker pools; no subagents, Mycelium, memory, skills, SCI
Extension Manager, Cedar, Chiasmus, autonomous daemon, or MCP/A2A/ACP. A2's
operation transcripts, SQLite storage, checkpoints, ContextSpec and TUI
architecture are not redesigned.

### Stop gate

Stop after A3a findings and fresh review. Do not begin A3b automatically;
`docs/A3A_FINDINGS.md` section 8 recommends it, including that it needs a new
`:agent/project-execute` profile rather than a widened A2 one.

---

## A2 (accepted, frozen at `bbagent-a2` / `bb4t-a2`)

## A2: useful semantic project world

A2 asks whether the model can do real project work through a small composable
semantic capability set while keeping persistent SCI as its working interface.

A1.1 produced the entry signal: the model discovers its surface and correctly
reports that directory enumeration is missing. `project/list` is the answer to
that specific report.

### Delivered

- **`project/list`** (bb4t): one directory deep, sorted inert data, symbolic
  links described but never followed, bounded by `:project/list-max-entries`.
- **`project/search`** (bb4t): regex over file contents returning
  `{:path :line :text}`, same traversal rules, skipping non-UTF-8 files, with a
  measured per-line matching budget.
- **`project/stat`** (bb4t): `{:path :kind :bytes :digest}`, or `:absent`. The
  digest is the coordinate an edit anchors to.
- **`project/edit`** (bb4t): version-anchored mutation. An edit must state the
  version it believed; a stale base is refused as a conflict rather than
  applied, and there is no way to spell a blind overwrite. Compare-and-swap by
  observation, not atomically — see the nonclaims.
- **Durable replay semantics** (bb4t + bbagent): recovery reconstructs a
  session's computational state without re-observing or re-actuating the
  project. Each evaluation records the semantic operations it invoked; recovery
  re-executes the Clojure and substitutes those recordings at the operation
  boundary, failing closed on any divergence. This closed the milestone's one
  blocking defect and generalized it: re-running a recorded `project/read` was
  as wrong as re-running a recorded `project/edit`.
- **Three capability profiles**: `:agent/project-read` is frozen and still
  reproduces the recorded A0/A1/A1.1 surface exactly, pinned by test;
  `:agent/project-survey` adds listing, search and stat and stays read-only;
  `:agent/project-develop` adds `project/edit` and is the session default.
  `resume!` inherits the profile recorded in the session's start coordinate, so
  an A0-era session is never resumed into a wider surface.
- **`:derived` orientation is the default**, replacing `:grounded`. This was
  forced rather than chosen: `:grounded` states limits as prose, including "you
  cannot enumerate a directory", which became false the moment `project/list`
  was granted. `:derived` generates its claims from the surface, so it picked up
  each new operation with no prompt edit and denies nothing the context grants.
- **Product claims reconciled with runtime behaviour**: the static base prompt
  no longer denies editing authority the default profile grants, and states
  closure rather than absence so it cannot go stale again. Stale profile
  defaults in the `session/start!` docstring and the CLI usage text are
  corrected.

### Delivered by the dogfood

Defects that only appeared from using it, all fixed; see `docs/A2_FINDINGS.md`:

- the twelve-action turn budget was sized for a read-only surface and cut the
  model off as it reached the file it had navigated to correctly;
- an oversized value reported its size and no content, so `project/read` was
  effectively unusable above ~4KB;
- the bounded vocabulary was 26 symbols with no `fn` or `defn`, so the agent
  could not compose helpers at all;
- a refusal reached the model as its category alone, with the kernel's own
  diagnostic message thrown away.

### Evidence status

- deterministic suites: bbagent 149 tests / 1170 assertions, bb4t 25 tests /
  198 assertions including the 96-case authority corpus, 0 failures;
- live comparison, 3 repetitions per arm, arms alternated: complete answers
  0/3 before, 3/3 after, with zero REPL errors after;
- live self-dogfood against this repository: 13 actions / 5 errors before the
  fixes, 6 actions / 0 errors after;
- live edit → process exit → resume → continue: six forms reconstructed
  exactly across a process boundary, the edited file byte-identical;
- native image built from local coordinates with **no new reachability
  metadata, build flag, or dependency**; authority in the image shows the A2
  grants with `:projected-class-count 0` and `:supplied-import-count 0`
  alongside all 35 negatives, and the build gates on a replay scenario that
  proves neither the observation nor the change is repeated;
- **37 PTY gates pass**, including resuming a session that edited a file — which
  the previous proof deliberately did not attempt, because it was impossible.

### Still open in A2

Nothing blocking. `project/test` was deliberately excluded; A3a answered the
substrate question underneath it and recommends a `project/run` primitive
instead. Neither shell nor Git capability was added. The
measurement target also needs revisiting: the A1.1 harness's
`concludes-limitation?` scored the right answer while enumeration was missing,
and now scores the wrong thing.

Three nonclaims a reviewer should weigh before accepting: `project/edit` is
compare-and-swap by observation; the expanded `base-allow` is argued pure and
corpus-checked rather than proved; and a capability declaring no effects is
assumed to be a pure function of its arguments.

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
