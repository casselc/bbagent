# A3b Findings: one semantic execution capability

> **Frozen at `bbagent-a3b` / `bb4t-a3b`**, retroactively, at this document's
> own evidence coordinate `861bea5` (runtime `84201fc`, bb4t `227d3854`).
>
> **Later note (A3c).** Section 5 records a property A3b measured and declined
> to overclaim: excluded paths were hidden from the workload's ordinary view,
> and a workload that wanted to could remount the raw export, because it was
> root inside the machine. A3c closed that — the guest is now a pinned image
> whose prelude drops to an unprivileged, capability-free identity after it
> finishes mounting. **Nothing in this document has been changed to hide the
> progression**; what it says was true of A3b and is no longer true of the
> current substrate. See `docs/A3C_FINDINGS.md`.

## 0. For review

**Verdict: PASS, recommended.** One capability, one new profile, one host-owned
executor. The three frozen profiles are unchanged, the model's execution surface
is three arguments wide, and a run whose project moved under it cannot be read
as verification.

**The question A3b owned**

> Can one minimal semantic `project/run` capability expose the proven isolated
> execution substrate to bounded SCI without creating another authority path,
> weakening A2 replay semantics, or making unanchored execution look like
> verification evidence?

**Answer: yes**, with one property stated more narrowly than the phrase
"excluded paths are not visible" suggests. See section 5; it is measured, gated,
and not glossed.

**The boundary, in one picture**

```
  (project/run {:argv [...] :cwd "." :timeout-ms N})    <- all the model may say
        |
  bb4t :project/run          validation, limits, result semantics
        |
  bb4t.execution             -describe / -execute; knows nothing of machines
        |
  bbagent.executor           version approval, tool bundle, ceilings   <- host policy
        |
  bbagent.worker             one ephemeral machine, project read-only
        |
  smolvm 1.7.5
```

**Evidence**

| What | Measured |
|---|---|
| native gates, all entered through `project/run` | 43 — version 5, authority 8, isolation 14, unstable-input 5, replay 7, dogfood 4 |
| A3a's own gates, re-run from the A3b image | 13 probe + 6 lifecycle + 4 dogfood, unchanged |
| authority, default profile | 58 negatives refused (was 51) |
| authority, execution profile | `project/run` granted, 20 negatives refused |
| projected classes / supplied imports | 0 / 0, both profiles |
| deterministic suites | bbagent 217 tests / 1419 assertions, bb4t 25 / 199, 0 failures |
| TUI PTY gates | 37/37 |
| image | `sha256:9b4f5b21…`, 76 876 032 bytes, no new reachability metadata, build flag, or dependency |
| executor coordinate | `sha256:8ed0e559…` — identical from the JVM and from the image |
| live dogfood | 3/3 arms finished: edit→verify→finish, resume with no second execution, unstable input reported distinctly |

`artifacts/a3b-evidence.edn` is the authoritative record; any figure quoted in
prose is quoting that file.

**Decisions this needs from a reviewer**

1. `:project/execute` is classified `:actuation`. A recovery cannot know whether
   the command it would re-run is a test or a deployment, so it never re-runs
   one. Accept, or argue for a third effect kind.
2. Excluded paths are hidden from the workload's ordinary view and **not** from
   a root workload that remounts the export. Accept the narrower claim, or fund
   a host-side filtered export. *(A3c did neither: it made the workload
   unprivileged, so the narrower claim became the stronger one.)*
3. An unrecognised machine-manager version is refused. The approved set is one
   version, because one is what has been measured.
4. `:cwd` is validated lexically, never against the host tree — the directory
   the command actually enters is the disposable workspace. A `:cwd` that does
   not exist there is reported as `:worker-failure`, not as a failed command.

---

## 1. What the model can say, and what it cannot

The entire model-facing surface:

```clojure
(project/run {:argv ["bb" "script/a3a-source-check.clj"]
              :cwd "."             ; optional, defaults to the project root
              :timeout-ms 120000}) ; optional, defaults to the context maximum
```

Anything else in the map is refused rather than ignored — `exact-keys!` on the
option map, so `:tools`, `:project-root`, `:environment` and `:network` are
errors and not silent no-ops. That matters more than it looks: a caller who
believed they had disabled the network and was ignored is worse off than one
who was told they cannot ask.

What stayed on the host side, with no model-facing spelling at all:

| Host policy | Where it lives |
|---|---|
| which project | bb4t's own `:project/root` resource |
| tool bundle directory | `bbagent.executor`, from `--tools` / `BBAGENT_EXECUTOR_TOOLS` |
| machine manager and version approval | `bbagent.executor` |
| CPU, memory, network, mounts | `bbagent.executor` ceilings, `bbagent.worker` |
| guest environment | `bbagent.worker/guest-environment`, constructed not filtered |

The tool bundle is the one worth naming explicitly. `bbagent.worker` accepts
absolute host directories as `:tools` and mounts them read-only into the guest.
That is safe only because its caller is trusted host code. If a model could
supply `/home/user/.ssh` as a "tool path", it would have a host-read authority
path that the machine boundary does nothing to close, because the read happens
on the host side of it. `project/run` has no argument that names a directory.

## 2. The seam

`bb4t.execution` is two functions. `-describe` returns inert data identifying
the environment; `-execute` runs one already-validated request. bb4t canonicalises
the description itself rather than trusting it, so a host implementation that
returned a live object fails when the runtime is built, not when a coordinate is
later found to be unreproducible.

The request carries the project root:

```clojure
{:project/root <Path> :argv [...] :cwd "." :timeout-ms N
 :stdout-max-bytes N :stderr-max-bytes N}
```

This is deliberate and is the one design choice most likely to be questioned.
The alternative — the executor holding its own root — was rejected because it
creates two answers to "which project is this session about" that nothing forces
to agree. bb4t is authoritative about that for `project/read`, `project/edit`
and everything else; execution is not the place to introduce a second opinion.

A Context granting `project/run` and having no environment bound is refused at
creation, not at first call. A session that believes it can verify its own work
makes different decisions from one that knows it cannot, and it should find out
before it has made any of them.

## 3. What a result says

```clojure
{:status :completed
 :exit 0
 :stdout "..."  :stdout/bytes N :stdout/truncated? false
 :stderr "..."  :stderr/bytes N :stderr/truncated? false
 :duration-ms N
 :worker/disposition :terminated
 :project/input-stable? true
 :project/input-coordinate "sha256:..."
 :executor/coordinate "sha256:..."}
```

Four statuses, kept distinct because collapsing any two of them loses something
a caller would act on:

| `:status` | means | `:exit` |
|---|---|---|
| `:completed` | the command ran and exited | present, whatever it chose |
| `:timeout` | the deadline fired; the machine was destroyed | **absent** |
| `:worker-failure` | the command never started | **absent** |
| `:project-changed` | the project moved while it ran | see section 4 |

A program that exits 124 and a host deadline are different events and are
reported as different events. No exit code is invented for a workload that did
not exit.

Stream budgets come from the Context's limits, and bb4t rechecks the returned
sizes rather than trusting the environment to have honoured them: an executor
that returned more than its budget did not enforce it, and that fails closed
instead of being trimmed into looking as though it had worked.

## 4. A project that changed is not a project that was verified

A3a already reported `:project/input-stable? false` when the before and after
coordinates differed. Leaving that as a flag alongside `{:status :completed
:exit 0}` would have been a trap: the shape a caller pattern-matches on is the
status, and a green test run against a tree that moved underneath it is not
evidence about either the old tree or the new one.

So the status is replaced, not annotated:

```clojure
{:status :project-changed
 :process/status :completed
 :process/exit 0
 :project/input-stable? false
 :stdout "..." :stderr "..." :duration-ms N
 :executor/coordinate "sha256:..."}
```

The process outcome is preserved but demoted, so nothing is lost and nothing can
be mistaken. No `:project/input-coordinate` is present, because there is no
single project state the run saw. The operation's docstring tells the model to
re-run if it needs an anchored answer, which puts it into the derived orientation
automatically rather than into prompt prose that would go stale.

A3b does not attempt to make the overlay a filesystem transaction. A3a recorded
that start/end bracketing detects movement and does not prevent a workload from
seeing a concurrent edit; that is still true, and this is the honest response to
it rather than a fix for it.

## 5. Exclusions, and the limit of hiding

> Superseded by A3c for the limit specifically; the mechanism below is
> unchanged. What A3c added is that the workload can no longer undo it.

**The invariant A3b was asked to make true:** every project path visible to the
workload is represented in the project-input coordinate, or is intentionally
hidden from the workload.

`bbagent.snapshot` now returns `:snapshot/excluded-paths` — the paths its walk
actually refused, at whatever depth it found them — and `bbagent.worker` hides
exactly that list. The hidden set is the excluded set by construction, not by
two lists being kept in agreement. In the guest prelude, after the overlay is
mounted, each hidden path is removed from `/work` (which writes a whiteout into
the upper layer; the read-only lower is untouched) and the raw export at
`/input` is covered with an empty tmpfs.

Measured in a real machine, with `.git` and a nested `target` excluded:

```
ls -a /work         .  ..  README.md  src          (.git gone)
ls -a /work/src/deep  .  ..                        (target gone)
ls -a /input        .  ..                          (export masked)
cat /work/.git/config   No such file or directory
cat /input/.git/config  No such file or directory
```

**And the limit, measured rather than assumed:**

```
umount /input   ->  rc=0  ->  ls -a /input  ->  .git README.md src
                              cat /input/.git/config  ->  secret-git-config
```

The workload is root inside the machine. There is no privilege separation in the
guest, so anything the guest can reach, a deliberately adversarial workload can
reach. The property A3b claims is therefore the narrower one: **no excluded path
is visible in a workload's ordinary view, and the hidden set equals the excluded
set.** It is not a privilege boundary, and the test suite contains a test that
*demonstrates the remount* precisely so that the claim cannot quietly drift
upward later.

What this does and does not cost. It does not weaken host protection: the export
is read-only, so a workload that remounts it can read `.git` and cannot write
anything. What it means is that the coordinate's completeness is a property of
cooperative workloads, not of hostile ones. Making it hold against a hostile
workload needs a host-side filtered export — a hardlink mirror of the
non-excluded tree, which costs a link per file and no data copy — and that is a
larger change than A3b should carry. It is the obvious next step if the
stronger property is ever wanted.

## 6. Replay: reproduce, never repeat

No replay logic specific to execution was added anywhere. `:project/execute` is
classified `:actuation` in `bb4t.catalog/effects`, and A2's transcripts do the
rest:

- **record** — the run happens, and its receipt carries the result and a strict
  coordinate over it;
- **replay** — the receipt is returned; the operation is never invoked;
- **legacy** — a historical form with no receipt is refused, because there is no
  record saying the change it would make has already been made.

The measurement that makes this more than an assertion is the executor's own
invocation counter, which a replay must not move:

```clojure
(def verification (project/run {:argv ["bb" "--version"] :timeout-ms 120000}))
```

record → 1 execution. Process exit, fresh Context, replay of the same source →
still 1 execution, the same result value restored, and `verification` bound in
the rebuilt Context. Legacy replay of the same source → still 1 execution and a
`:actuation-without-transcript` refusal. A recovery that reconstructed a run
*and also performed it* would leave the same result and a different count, and
the count is the only thing that tells those two apart from outside.

## 7. Compatibility: an unmeasured manager is refused

A3a measured smolvm 1.7.5. Its central finding — that killing the manager's
front-end leaves the machine running, and that cleanup means reaping the process
tree — is a fact about an implementation, not a guarantee of the command line.
A version nobody has measured may be better, worse, or differently wrong.

`bbagent.executor` therefore refuses a version outside its approved set, which
currently has one member. A trusted host may override; the override is recorded
in the execution environment's description as `:executor/approval :host-override`,
so a run made under one is never indistinguishable from a run made under an
approved version. The refusal is proved against a real manager rather than
reasoned about: the approved set is host configuration, so the native gate hands
it a set the real version is not in and watches it refuse.

## 8. The execution environment in the coordinates

```clojure
{:executor/type      :bbagent/smolvm-worker
 :executor/manager   "smolvm"
 :executor/version   "1.7.5"
 :executor/approval  :recognized
 :executor/guest     {:privilege :root :environment :constructed
                      :host-environment :not-inherited}
 :executor/network   :none
 :executor/workspace {:model :overlayfs :project-mount :read-only
                      :lifecycle :ephemeral-machine-per-execution
                      :excluded-paths :hidden-from-workload}
 :executor/exclusions [".git"]
 :executor/tools     {:bundle :babashka/static :version "babashka v1.13.219"
                      :contents ["bb"] :coordinate "sha256:..."}
 :executor/ceilings  {:worker/cpus 2 :worker/memory-mib 2048 ...}}
```

No host path, no secret, no handle — gated, by a check for any string beginning
with `/` and for the host's own home directory by name. It is bound into
`:context/effective`, so it is inside the Context coordinate: a session recorded
against one execution environment cannot be silently resumed against a
materially different one.

## 9. Authority

`:agent/project-execute` is `:agent/project-develop` plus exactly one
capability, asserted as set equality rather than by listing. The three frozen
profiles are asserted unchanged by their literal capability sets.

The default-profile negative corpus grew from 51 to 58: the new probes cover the
seam on both sides (`bb4t.execution`, `bbagent.executor`, `bbagent.bb4t/create`)
as well as the operation name itself. A session on the default profile still
cannot spell `project/run`.

A second corpus runs *inside* the execution profile, which is the one that
matters for A3b: `project/run` is granted, and 20 probes covering the raw worker,
the process primitive, the snapshot, the executor, the JVM process API, and every
host-policy argument someone might try to pass to `project/run` are all refused.
`:projected-class-count 0` and `:supplied-import-count 0` hold in both.

## 10. Nonclaims

- **Hiding is not a privilege boundary.** Section 5. The machine is the boundary.
  *(A3c changed this: with a capability-free workload it is now enforced. The
  machine remains the boundary that host isolation rests on.)*
- **One manager version is approved because one has been measured.** Nothing here
  claims 1.7.6 is worse; it claims nobody has looked.
- **The overlay's lower layer is still live.** A3a's finding stands. A3b detects
  movement and reports it distinctly; it does not prevent it.
- **`:cwd` is validated lexically.** It cannot escape the project, and it is not
  checked for existence, because the tree it will be resolved against is the
  workspace and not this one.
- **The tool bundle is trusted, not verified.** Its digest identifies which bytes
  ran; nothing here says those bytes are benign.

## 10a. The live dogfood

A local Qwen3.6-27B under `:agent/project-execute`, one fixture per arm, and a
task that cannot be short-circuited: the fixture's check fails, and nothing in
the project states the required value or the command except the check itself.

**verify — inspect, edit, verify, finish.** Finished in 14 actions. It read the
check and the source, called `project/run` with `:timeout`, was refused before
the call reached the execution environment, called `(doc project/run)`, re-ran
correctly and got `exit 1` with `FAIL: ... found 7`, offered a whole file as
`:base` and was refused, called `project/stat`, edited anchored to the digest,
re-ran and got `exit 0` with `quarry check OK: spacing 9`. Two executions, both
anchored, to two different input coordinates -- the second differs because the
project genuinely moved between them.

Worth noting on its own: three `project/run` calls produced two executor
invocations. The refused one never reached the environment, which is the
argument validation being a bb4t property rather than a worker one.

**resume — the historical run reconstructs.** Finished in 10 actions, then the
session was closed and rebuilt from its durable events. All 10 forms
reconstructed, nothing re-observed, no legacy fallback, and the executor's
invocation count was 1 before the resume and 1 after. A live session that
edited and verified its own work came back without verifying it again.

**unstable — a moved project cannot masquerade.** The host rewrote a file in the
project every 400ms for the whole turn, rather than once at a guessed moment.
Both of the model's executions came back `:status :project-changed`, with no
`:exit` and no `:project/input-coordinate`, and it still completed the task. It
was told its result was not anchored instead of being handed an apparent
success.

## 10b. What the dogfood found about the dogfood

The first live run is worth recording because the model was right and the
harness was wrong. Its fixture's check script is generated from Clojure string
literals, and the escaping produced `#"\\(def spacing (\\d+)\\)"` in the
emitted file -- a regex matching a literal backslash, which never matches. The
check could not pass however the project was edited. That was verified against
a hand-written copy of the script rather than against the one the harness
actually writes, which is the same mistake as writing a build gate against how
the output was expected to look.

What the model did with an unpassable check is the interesting part. It
explored the project, read the check, ran it and got `exit 1`, made an anchored
edit using `(project/stat "...")` inline as the `:base`, re-ran and still got
`exit 1`, read the file back and saw its own edit was applied, tried to debug
the regex in SCI and was correctly refused because `re-find` is not granted --
and then ran `bb -e` through `project/run` to evaluate the regex inside the
worker, getting `MATCH: nil`. It diagnosed that the check was broken rather
than concluding its edit had failed.

That is the composition the milestone is for: a task-specific investigation
built out of one granted execution primitive, with no new host tool. It is also
the strongest available evidence that `project/run` is usable rather than
merely present, and it was produced by a bug rather than by design, so it is
recorded here rather than claimed as a designed result.

## 11. What A3b deliberately did not do

No `project/test`, `project/build` or `project/lint`; no vocabulary of host
tools; no generic shell capability; no background processes or dev-server
lifecycle; no Git tooling; no network enablement; no package-installation
policy; no memory, skills, subagents or SCI Extension Manager. A2's operation
transcripts, SQLite storage, checkpoints, ContextSpec and TUI architecture are
unchanged.

The session default is still `:agent/project-develop`. Execution is selected
explicitly, because it should be a thing an operator decided to hand over.
