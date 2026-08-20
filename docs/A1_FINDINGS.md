# A1 Findings

## 1. Verdict

**Pass.** A native interactive TUI is now the preferred operator interface to
bbagent, and it is only a projection and controller over the existing
`AgentSession`, store, and bb4t semantic state. It introduces no second state
model and no second authority path.

The full build wrapper passes end to end against the TUI image, seventeen
PTY-driven native gates pass, the deterministic suite is clean, and the model
Context is byte-for-byte the A0 Context it was before the TUI existed.

Two defects were found by the native gate rather than by unit tests, and one
recovery hole introduced by the first implementation was found in review and
closed. Those are recorded in full below rather than smoothed over.

This is not a claim that bbagent is pleasant for open-ended work. The live
dogfood exposed a real orientation ceiling that A1 deliberately does not fix.

## 2. S0 Closure

S0a and S0b are integrated and frozen. Both integrations were history-preserving
fast-forwards; no measured or evidence commit was rewritten.

| Repository | Branch | Integrated from | Commits | Tag |
|---|---|---|---|---|
| bb4t | `bb4t/dev` | `bb4t/sqlite-spike` | 3 | `bb4t-s0a` |
| bbagent | `main` | `s0/sqlite-store` | 19 | `bbagent-s0b` |

Complete frozen coordinates are in `docs/S0_CLOSURE.md`.

**SQLite default policy.** `storage/backend` maps an unspecified backend to
`:sqlite`; `session/start!` and `session/resume!` both default to `:sqlite`.
`--store file|sqlite` and `:store-backend` remain explicit everywhere.

**Legacy file-backend policy.** The file backend remains supported, readable,
and the reference semantics. Backend selection is never inferred, probed, or
migrated. An existing file session must be opened with `file`; opening a session
with the wrong backend fails closed as `:session-recovery-failure` rather than
reinterpreting the other backend's state. There is no migration, import, or
merged discovery. `existing-session-backend-identity-test` proves the file
session's events survive intact, that nothing leaks into the SQLite store, and
that explicit `file` selection still resumes it.

## 3. TUI Technology

**Selected: `de.timokramer/charm.clj 0.2.74`, unchanged, driving JLine
directly.** ADR `docs/architecture/0002-a1-tui-runtime.md` records the decision
and the rejected alternatives.

The decision was made from the dependency graph, not documentation. Charm's
published README says JLine 3; its actual `deps.edn` targets **JLine 4.3.1 with
the same `jline-native` exclusion bb4t already uses**. The expected
major-version mismatch did not exist.

| Question | Finding |
|---|---|
| JLine in bb4t | `4.3.1` |
| JLine charm expects | `4.3.1`, identical exclusion |
| Works unchanged? | Yes, on the JVM and natively |
| core.async | charm requests `1.9.865`; bb4t ships `1.8.741`. All 25 charm namespaces load and run against `1.8.741`; core.async use is confined to `charm/program.clj` |
| Reflective sites in charm | Exactly two, found by compiling with `*warn-on-reflection*` |

**Native-image requirements — two additions, both observed as real failures:**

1. `--initialize-at-build-time=org.jline.utils.InfoCmp$Capability`
   (`resources/META-INF/native-image/bbagent/bbagent/native-image.properties`).
   graal-build-time initializes Clojure namespaces at build time, so charm's
   terminal namespace puts JLine enum constants in the image heap while JLine
   defaults to run-time initialization. Without this the image **fails to
   build**, naming the exact type.
2. Reflection metadata for `org.jline.keymap.KeyMap` and `java.util.ArrayList`
   (`reachability-metadata.json`). `charm/input/keymap.clj:134` calls the
   varargs `KeyMap.bind` with untyped arguments; `charm/render/core.clj:199`
   constructs an `ArrayList` reflectively. Without this the image **builds and
   then dies at run time** inside `clojure.lang.Reflector`.

bb4t's existing build already supplied everything else JLine's FFM provider
needs: `--enable-preview`, `-H:+SharedArenaSupport`, `-H:+ForeignAPISupport`,
`--enable-native-access=ALL-UNNAMED`, and a populated `"foreign"` reachability
section.

**Dependencies added:** one, `charm.clj 0.2.74`, in the inactive-by-default
`:app/bbagent` profile. JLine and core.async were already bb4t dependencies. The
image grew 9,240,576 bytes (65,603,840 → 74,844,416).

**Classes added to model SCI: none.** The TUI is AOT-compiled trusted code using
ordinary interop, so it needs no `babashka.impl.classes` registration; that
mechanism exists only to expose classes to interpreted SCI and using it here
would widen authority for nothing.

### Alternative evaluated after implementation

The sibling application `bbf1` has since replaced charm entirely with direct
JLine 4.0.12 plus `no.cjohansen/nexus`, a serialized mailbox, and a coarse
statechart. Reviewing that branch:

- its own extraction register marks **every** seam *keep bbf1-local* or *retain
  in bbf1*; the intended reusable home, `cljline`, is still at design stage and
  forbids a production JLine dependency;
- the measured migration was performance-neutral (allocation deltas of −5.1%,
  +2.2%, −0.6%, +1.2%), and the earlier charm-fork spike actually regressed
  (render p95 14.8 ms → 23.0 ms). The motivation was ownership and dependency
  removal, not speed, and bbagent's TUI is far lighter than bbf1's 30fps replay.

Adopting it into A1 would mean forking roughly 3,000 lines its owner has judged
not extraction-ready. Recorded as a candidate follow-on with concrete triggers
in section 14 instead.

## 4. Architecture

```text
durable application state          transient TUI state
  AgentSession, Store, Context       focus, scroll, input buffer,
  session/run coordinates            selection, modal, size, event window
        |                                     ^
        | projection                          |
        v                                     |
   bbagent.tui.viewmodel  ------------------->+
        |
        v
   bbagent.tui.render  (pure: state -> string)
```

```text
Key -> bbagent.tui.state (pure reducer) -> [view' commands]
                                              |
                          inert command values v
                             bbagent.tui.command worker thread
                                              |
                  session/agent/store/bb4t public seams
                                              |
                              durable journal + checkpoint
                                              |
                        transient result messages -> new view
```

| Namespace | Role | Pure? |
|---|---|---|
| `bbagent.tui.viewmodel` | projections from durable state to view data | yes |
| `bbagent.tui.state` | view state and key/result reducer | yes |
| `bbagent.tui.render` | view state to terminal text | yes |
| `bbagent.tui.command` | worker; the only namespace performing semantic work | no |
| `bbagent.tui.app` | charm wiring; the only namespace touching the terminal | no |

**Confirmation that the TUI is a projection/controller.** No domain truth is
stored in UI state: the view holds a session handle as an opaque reference and
recomputes every displayed field from `session/*`, `store/*`, and
`context/describe`. The strongest evidence is behavioural rather than
structural — after quitting and resuming in a **new process**, the conversation
pane redisplays the earlier turns, because it is derived from the durable
journal and never from anything the previous process held.

The reducer returns `[state commands]` where commands are inert data, so key
handling is tested without a terminal, a provider, or a store.

## 5. Agent Integration

Human input reaches `AgentSession` unchanged. `Enter` produces
`{:command/type :session/submit-message :text ...}`; the worker calls
`agent/turn!` — exactly what the CLI calls. The model loop was not rewritten and
durable append ordering is identical to the CLI's, because the worker executes
commands one at a time in submission order. Only reporting is asynchronous.

The transient notification vocabulary is `:bbagent/activity`,
`:bbagent/conversation`, `:bbagent/events`, `:bbagent/capabilities`,
`:bbagent/header`, `:bbagent/repl-result`, `:bbagent/turn-complete`,
`:bbagent/sessions`, `:bbagent/session-switched`, `:bbagent/error`. These are
notifications over authoritative state, never a second durable event model.

**Two charm hazards, handled rather than worked around.**

1. charm's loop **rethrows and terminates the program** on a `:error` message.
   Every worker failure is therefore caught and converted to a `:bbagent/error`
   domain message. `worker-reports-errors-as-domain-messages-test` pins this.
2. charm creates its message channel internally and never exposes it, so no
   outside thread can post. Results arrive through a single self-sustaining
   pump command that blocks briefly on the worker queue and always returns a
   message. Exactly one command is ever outstanding, so exactly one core.async
   dispatch thread is ever occupied.

Both disciplines are independently used by `bbf1`: it catches every fetch
failure into a domain `:feed-result`, and its playback drives frames with a
`program/cmd` that sleeps to a deadline and whose handler re-arms it.

**Known limitation.** The worker is strictly serial and there is no
cancellation, so a command submitted during a model call queues behind it. This
sits inside A0's existing "no hard isolation" nonclaim; it is not a new one. See
section 14.

## 6. Context and Capability Projection

**Data source:** `bb4t.context/describe` on the session's live Context. Nothing
is hard-coded. The pane shows the profile, the requested/authorized
capabilities, the effective grants, the projected Vars and namespaces, the
limits, and the projected class and supplied import counts, all read from the
ContextSpec and context description.

Rendered from the real A0 context:

```text
profile :agent/project-read
capabilities
  :data/json-read
  :data/json-write
  :project/read
Vars
  data.json/read
  data.json/write
  project/read
limits
  :project/read-max-bytes 1048576
classes 0  imports 0
```

`capability-projection-is-not-hardcoded-test` proves a capability the runtime
does not grant cannot appear; `real-context-capability-projection-test` pins the
projection against the actual bounded context. The context-spec digest the pane
reads, `sha256:56afcaaf18ea2ef16…`, is the same coordinate A0 froze.

**Did the A0 orientation problem improve? For the operator, yes. For the model,
no — and A1 could not have improved it.** The pane is human-facing; the system
prompt is unchanged, so nothing the pane renders reaches the model. Section 12
records what the live run actually showed.

## 7. Storage Use

The TUI depends only on `bbagent.storage` and `bbagent.store`. No view logic
touches `bbagent.sqlite-store`, JDBC, SQL, or schema details, and the TUI works
against the file backend.

**Incremental event consumption.** The first read is a bounded
`store/recent-events`; every later read is `store/events-after` on the last seen
event ID. The view window is bounded and drops old rows rather than growing, so
the store stays the history of record.

Review caught that the first read was originally
`(take-last n (store/events ...))` — bounded in the view but not at the storage
layer, decoding and hydrating the whole session to show the newest events, which
partly gave back what S0b's indexes bought. `store/recent-events` was added to
the contract: SQLite selects `ORDER BY seq DESC LIMIT ?` and restores ascending
display order; the file backend trims its already-recovered cache. This changed
query capability, not durable semantics. `tail-read-is-bounded-test` pins the
result against the full history on both backends.

The bundled SQLite has FTS5. No search was added.

## 8. Session UX

New (default SQLite), resume, list, and inspect all work. `Ctrl-S` lists
sessions for the selected backend with the current session marked; `Up`/`Down`
select and `Enter` resumes. Resume closes the outgoing session first, so it is
checkpointed and its store released before another opens, and the view clears
event rows, cursor, conversation, and REPL log so nothing leaks across sessions.

No merged cross-backend discovery, migration, tagging, search, archival, or
deletion.

## 9. Native Evidence

| Coordinate | Value |
|---|---|
| bb4t | `0ca6addef70278189aefa73df940dee0e9e607b4` |
| bbagent | `991517c870ed8aaba8c7520b00f5620ee7169cdb` |
| charm.clj | `0.2.74`, jar `a142eec611db7493949dc29847329f6be2bde2fb7aa500a8b421cda00211c27e` |
| JLine | `4.3.1` (already a bb4t dependency) |
| next.jdbc / sqlite-jdbc | `1.3.1118` / `3.53.2.1` |
| SQLite runtime | `3.53.2` |
| GraalVM / native-image | Oracle GraalVM `25.2.4+7.1` / `25.0.4` |

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| `dist/bbagent` | 74,844,416 | `e5af878aec12b3c45deecb13f317b0d14b40fcfc6a481d8b509ea39841b7416e` |
| `dist/libsqlitejdbc.so` | 1,093,888 | `f374da845a36d0a663521457f8e454413325e3b8247a15c2677426f4b15cf6ac` |

native-image took 43.8 s with 22 warnings. The full wrapper passed every S0b
gate against the TUI image: create, resume, non-vacuous CAS, ambiguous-exit,
transaction-crash, relocated sidecar, absent and digest-mismatched sidecar
failing closed before database creation, and an empty runtime temp directory.

**Seventeen native TUI gates, all passing** (`script/tui-native-proof.py`, real
PTY): TUI starts; header shows session and sqlite; capability pane and profile
render from bb4t metadata; event pane fills; session browser lists; operator
REPL mode; bounded evaluation; operator `def` accepted; clean exit with
checkpoint; sessions listed; resume starts, resumes the same session, shows
capabilities, **reconstructs operator state**, and exits cleanly.

Startup behaviour: the TUI paints its first frame and its capability pane
without operator action; resume painted in 2.1 s in the live dogfood.

The build used local repository clones at the recorded SHAs rather than the
public remotes.

## 10. Authority Regression

**Unchanged.** Same `:agent/project-read` ContextSpec, same three grants
(`#{:project/read :data/json-write :data/json-read}`), zero projected classes,
zero supplied imports.

Negative probes grew from 22 to **35**, all returning `:error` with category
`:bb4t-evaluation-failure`, and no forbidden database was created. The thirteen
added probes are `org.jline.terminal.Terminal`, `TerminalBuilder`,
`TerminalBuilder/terminal`, `InfoCmp$Capability`, `KeyMap`, `LineReader`,
`charm.program/require`, `charm.terminal/require`,
`charm.terminal/create-terminal`, `bbagent.tui.app/require`,
`bbagent.tui.command/require`, `bbagent.tui.command/start-worker!`, and
`bbagent.tui.state/require`.

`script/build-native` now fails closed if the count is not 35, if any TUI probe
is missing, or if projected classes or supplied imports are not zero.

The compiled image contains TUI capability. The model Context did not inherit
it.

## 11. Tests

**JVM: 99 tests, 759 assertions, 0 failures, 0 errors.**

- view-model projection: conversation turn kinds, large-result summarisation,
  capability projection from metadata and from the real context, header without
  secrets;
- reducer without a terminal: focus, input editing, space handling, submit,
  history, quit, Ctrl-C, scroll, resize, event selection and detail, error
  display, session browser selection and resume, session-switch state clearing;
- rendering: purity, and no line exceeding the terminal width at 40×12, 80×24,
  and 200×60;
- incremental events: initial window plus `events-after` equals the updated
  model without a reload; bounded window drops oldest; bounded first read equals
  the tail of the full history on both backends;
- agent integration through the worker with a fake provider: human message →
  REPL action → `project/read` → result → finish, with the TUI receiving the
  corresponding conversation and event updates;
- operator durability: definition survives resume on both backends; no
  conversation turn synthesized; partial mutation before error still
  reconstructs; orphan operator request fails resume closed;
- authority: the 35-probe negative corpus.

**Native:** the wrapper's S0b gates plus the seventeen TUI gates, run against
the actual relocatable distribution, separately from JVM unit tests.

**Live dogfood:** performed; see section 12.

## 12. Usability Observations

Two live scenarios against the A0 endpoint and model
(`Qwen3.6-27B-MTP-GGUF`, Lemonade OpenAI-compatible loopback).

### Scenario A — explicit files (the Phase 21 scenario)

Prompt: *"Read README.md and src/example/core.clj and explain this project.
Retain the README text with (def retained-readme ...) before you finish."*

Worked cleanly. Turn completed in **32.1 s**; four REPL forms, **all `:ok`**:

```clojure
(project/read "README.md")
(project/read "src/example/core.clj")
(def retained-readme "...")
retained-readme
```

The model explained both files correctly, including deriving that `(run)`
returns 17 from `start` 7 and `step` 5. Quit checkpointed; a **new process**
resumed in 2.1 s and answered the follow-up in 20.2 s, correctly reporting the
checkpoint phrase `"amber compass"` from the reconstructed `retained-readme`.
Both processes exited 0. The 56-event journal contains no API key,
authorization header, or credential (`sha256:889dcd36…`).

Useful: the conversation pane distinguished human, agent, and tool turns; large
structured results were summarised rather than dumped
(*"(336 characters; inspect the event for the complete value)"*); resume felt
coherent because the conversation reappeared from the journal.

### Scenario B — discovery required (the A0 failure mode, reproduced)

Prompt: *"What files does this project contain, and what does each one do?"*

**The turn failed.** The model exhausted the twelve-action limit; **11 of 12
REPL forms errored**:

```text
:error  (project/read)
:error  (project/contents)
:error  (dir project)
:error  (find-doc "project")
:error  (clojure-version)
:ok     (+ 1 2)
:error  (require '[clojure.java.io :as io])
:error  (require '[sci.api :as api])
:error  (find-doc #".*read.*")
:error  project
:error  (require '[project])
:error  (project/read "project/contents")
```

The TUI presented this correctly: a concise
`invalid agent action: Agent exceeded the A0 action limit` in the status line,
with structured detail reachable by selecting the event — no stack trace in the
conversation pane.

**The decisive observation:** the operator could see the exact three-Var
capability list on screen the entire time while the model guessed eleven
nonexistent Vars. The capability pane improves *operator* orientation and does
nothing for the model, because the prompt is unchanged.

The model also never tried the discovery Vars that **already work**. Verified in
this native image through the operator REPL:

```text
(apropos "")        => :ok   [data.json/read data.json/write project/read]
(doc project/read)  => :ok   arglists, docstring, and effects
```

`apropos` and `doc` are in `catalog/base-allow` and bb4t builds those
docstrings from the RuntimeCatalog. The 414-character system prompt names only
`project/read` and `def` in prose and never mentions that discovery exists. This
is a prompt gap, not an authority gap.

### Confusing, slow, or rough

- **Event status column truncates.** Checkpoint reasons render as `:sessi…` and
  `:opera…` because event rows use fixed pad widths instead of sizing from the
  pane. Cosmetic but it degrades the pane's usefulness.
- **`:bb4t/event` diagnostics dominate the event pane** — 15 of 56 events in
  scenario A. They crowd out semantic events; a default filter or separate
  diagnostics view is wanted.
- **No progress detail during a model call.** The header shows
  `waiting-for-model` and the UI stays responsive, but 32 s with no further
  signal is a long time. Streaming or step-level activity would help.
- **A failed turn costs the full action budget.** Twelve sequential model calls
  before failing is slow and expensive; earlier detection of repeated
  unknown-Var errors would fail faster.

Recorded, not fixed. No A2 feature was added during the session.

## 13. Known Nonclaims and Debt

- no A2 capabilities: no project listing, search, grep, editing, test running,
  or bounded execution;
- no hard worker isolation, CPU/memory/deadline bounds, or worker termination;
  the worker is serial and uncancellable, so a command issued during a model
  call queues behind it;
- **Ctrl-C cancels the pending input line only.** It does not stop a provider
  call, interrupt an evaluation, or terminate the process;
- no memory, embeddings, vectors, FTS, or session search;
- no storage or checkpoint redesign. The S0b long-session debt is untouched:
  checkpoints still repeat complete messages and replay forms, resume still
  validates the complete history, SCI state is reconstructed rather than
  persisted, and there is no object reachability or GC. A1 exploits the indexed
  `latest-checkpoint`, `events-after`, and bounded tail for UI efficiency only;
- no BuildManifest, and no durable executable/sidecar/toolchain provenance;
- no cross-platform or static-linking claim; Linux x86_64 glibc only;
- no complete JVM/native suite parity;
- no MCP, A2A, ACP, web UI, or HTTP server; no multi-agent behaviour;
- **product debt:** the TUI entry point calls `System/exit` after the session is
  durably closed, because core.async pooled threads keep the native runtime
  alive for roughly 48 s otherwise. This is charm/core.async debt sitting in the
  private CLI path after durable close;
- the operator REPL shares the model's Context and its evaluations are durable,
  so an operator can change what the model's replay reconstructs. That is
  intended, but it means operator actions carry real weight.

### Defects found during A1

| Defect | Found by | Severity |
|---|---|---|
| operator REPL evaluations not journaled, so an operator definition a later agent form depended on was never reconstructed and resume could fail its status-equivalence check | review | correctness |
| first event read decoded the whole session and trimmed | review | efficiency |
| typed spaces silently swallowed, corrupting multi-token REPL forms before evaluation | native PTY gate | correctness |
| quit lingered ~48 s after a correct checkpoint | native PTY gate | usability |

All four are fixed and pinned by tests. The native gate has now caught defects
in two consecutive milestones that JVM tests did not.

## 14. Recommendation for the Next Milestone

**Do a small capability-orientation milestone before A2.**

Scenario B is the evidence. The failure was not that the model lacked
`project/list` — although it does. The failure was that the model could not
discover what it *did* have, so it spent the entire action budget guessing and
never produced an answer, when one `(apropos "")` call would have told it
exactly what existed and let it answer *"I can only read files you name."*

That fix appears to cost almost nothing: `apropos` and `doc` are already granted
and already work natively. The change is to the prompt and possibly to a
capability preamble derived from the context description, plus a before/after
live trace as evidence. It is a prerequisite that makes every later capability
more usable, and it should not be smuggled into A1 or A2 — it needs its own
evidence, because it changes model behaviour.

**Then A2**, whose shape scenario B also clarified: `project/list` is the
concrete missing capability, and the request that exposed it is the most
natural thing an operator asks first.

Two smaller items worth folding into whichever milestone is next: size the event
row columns from the pane width, and give `:bb4t/event` diagnostics their own
view so they stop crowding semantic events.

**Deferred, with triggers rather than a date:** replacing charm with a
bbf1-style mailbox runtime. The trigger is A2 wanting real cancellation, where
charm's one-message-per-command model gets thin and a lossless `enqueue!` plus
task scopes is the right substrate. It would also remove core.async and with it
the `System/exit` workaround. Reconsider sooner if `cljline` matures enough to
make this a dependency rather than a fork.

**Deferred to BB2:** production/developer/evidence/worker build profiles, and a
BuildManifest distinguishing source universe, build profile, native artifact,
and reachability coordinate.

Stop here for fresh review. Do not begin the next milestone automatically.
