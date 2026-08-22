# A3a Findings: Isolated Project Execution

**Status: A3a complete. Recommended verdict PASS.**

## 0. For review

A3a asked one question:

> Can trusted bbagent/bb4t host code execute arbitrary project-owned code
> inside a hard-bounded worker, obtain structured results, terminate it
> reliably, and prove that the execution cannot escape into the authoritative
> host project, host secrets, or ambient network?

Yes, and the isolation is a property of how the filesystem is mounted rather
than of anything bbagent remembers to clean up.

**A3a adds no model-facing authority.** The bounded Context grants exactly what
A2 froze. There is no `project/run`, no `process/*`, no `shell/*`, no
`smolvm/*`. The authority negative corpus grew from 35 probes to 51 to say so
in the image itself.

### The shape of the boundary

```text
authoritative project root
      |  read-only mount            the host tree is never writable
      v
   /input                           overlay lower layer
      |  overlayfs, upper inside    copy-on-write, nothing copied in
      v
   /work                            writable, dies with the machine
      |
      v
   argv
```

Project code may do anything it likes to `/work`. None of it reaches the host,
because the only host filesystem the machine can see is mounted read-only and
the layer absorbing the writes lives and dies inside the machine.

### What the evidence covers

| Evidence | Result |
|---|---|
| bbagent deterministic suite | 181 tests, 1275 assertions, 0 failures |
| bb4t deterministic suite | 25 tests, 198 assertions, 0 failures |
| A3a isolation and bounds, natively | 13 gates |
| A3a lifecycle, natively | 6 gates |
| A3a dogfood, natively | 4 gates, against the built checkout |
| Authority | 51 negative probes denied, `:projected-class-count 0`, `:supplied-import-count 0` |
| A2 regression | all A2 gates still pass in the same image |

### Two results that had to be measured

Both are cases where the reasonable assumption was wrong, and both changed the
implementation. They are the substance of this milestone.

1. **Killing the manager process is not cleanup.** `SIGKILL` and `SIGTERM` to
   the machine manager both leave the machine *running* — a `_boot-vm` process
   survives, `machine ls` reports `running (eph)`, and it keeps consuming CPU
   and 280MB of RSS. The workload only appears to stop because the mount server
   died with the front end. This is the handoff's own warning relocated one
   level: the host stopped waiting, the workload did not stop. `bbagent.process`
   therefore destroys the process *descendants* first, which is what actually
   ends the workload, and collects them before destroying the parent because a
   dead parent has no descendants to enumerate.

2. **The overlay's lower layer is live, not frozen.** A copy would snapshot the
   project; an overlay reads through to the host tree as it is now. A host write
   during a run was visible to the workload, and the read came back *truncated*
   relative to what the host had written — so a concurrent edit can produce a
   torn read, not merely a fresh one. A run whose project moved under it
   therefore reports `:project/input-stable? false` and carries **no** input
   coordinate, rather than one naming a state the run only half saw.

### Decisions this needs

1. **Is one ephemeral machine per execution the right lifecycle?** It makes
   "a timed-out worker is never reused" true by construction rather than by
   policy: there is no reuse to get wrong. It costs a boot per execution, which
   measured at 0.52s.
2. **Is the read-only mount plus in-machine overlay the right project model?**
   It means no host-writable surface exists at all, so §12's requirement is
   satisfied by a mount flag rather than by cleanup discipline.
3. **Is bracketing the run with the input coordinate strong enough?** It detects
   a project that moved; it does not prevent the workload from having seen the
   move. For a single-player agent whose only other writer is its own serialized
   `project/edit`, this was judged sufficient.
4. **Should A3b expose `project/run`?** Section 7 recommends it, with a new
   profile rather than a widened A2 one.

---

## 1. What was built

| Namespace | Responsibility |
|---|---|
| `bbagent.process` | Bounded host subprocess: a deadline, output budgets, and a process tree that is actually dead when the call returns. |
| `bbagent.snapshot` | The project input manifest and its coordinate. |
| `bbagent.worker` | The one place that knows a machine manager exists. |
| `bbagent.a3a-smoke` | Native evidence phases. |
| `script/a3a-source-check.clj` | The dogfood target: a real babashka check of real project invariants. |

`bbagent.process` also replaced the unbounded `ProcessBuilder` in
`bbagent.coordinates`. Reading the project's git coordinate at session start
previously had no deadline at all, so a repository on a stalled filesystem
could block session start indefinitely.

**No native-image change was required.** `bbagent.coordinates` already compiled
a `ProcessBuilder` into the image to read git coordinates, so A3a added no
reachability metadata, no build flag, and no dependency — the same result the
A2 repair had.

That existing use is also why the image's ability to start a subprocess was
verified rather than inferred: `coordinates/git-command` swallows `Throwable`
and returns `nil`, so a broken `ProcessBuilder` in the image would have looked
exactly like a project that is not a git repository. The `describe` phase asks
the question directly.

The native gate then earned its place immediately. The snapshot walk called
`.size` on a `BasicFileAttributes` without a type hint, which Clojure resolved
reflectively; that works on the JVM and fails in the image against the
JDK-internal implementation class. The deterministic suite was green, the
native run was not. Both new call sites are now hinted and the A3a namespaces
compile with no reflection warnings.

## 2. Cost, and why the design is an overlay rather than a copy

Copying the project into the machine was the simpler design and the handoff
explicitly permitted it. It was rejected on measurement, against a 283MB /
4001-file tree:

| Approach | Total wall | Net of 0.52s boot |
|---|---|---|
| `cp -a /input /work` | 2.698s | ~2.18s |
| overlayfs mount | 0.884s | ~0.36s |

The 0.36s includes a full traversal of the merged tree, so the mount itself is
close to free. What matters is not the ratio but the shape: copying scales with
tree size and mounting does not. The copy also had a ceiling that was not
obvious — it lands in the machine's root overlay, which defaults to 2GiB, so a
large worktree would have hit `ENOSPC`.

Copy-on-write semantics were verified rather than assumed: read-through from the
lower layer, copy-up on write (including a 50MB file), whiteouts on delete,
opaque whiteouts on directory removal, and `rename` across the overlay, which
build tools do constantly. After all of it the host tree was byte-identical.

One instructive failure: the first overlay mount failed with `wrong fs type, bad
option, bad superblock`, because the upper layer was on the machine's own root,
which is itself an overlay. Overlayfs refuses an overlayfs upperdir. The upper
lives on the machine's ext4 disk instead — not on tmpfs, so build output
competes with the machine's disk rather than with the compiler for RAM.

## 3. Isolation, as measured

| Property | How it was established |
|---|---|
| Host project unchanged | Workload overwrote a file, deleted another, created a third, and believed it succeeded. Host byte-identical. |
| Host files outside the project unreadable | A sentinel readable by the host, absent to the workload. The probe distinguishes *unavailable* from *not looked at* by asserting the failure status and message. |
| Host environment secrets unreachable | The manager forwards no host environment. Probed with sentinels — including an `OPENAI_API_KEY` — in the environment of the launching process itself. |
| Environment is constructed, not filtered | The workload receives exactly `HOME`, `TMPDIR`, `LANG`, `PATH`, plus what the caller declared. A caller cannot redefine those four. |
| No network | No default route, and an outbound attempt fails rather than hanging. Networking is off unless asked for, and A3a never asks. |
| Symlinks cannot widen the boundary | A link out of the tree fails the snapshot with an actionable error rather than being followed, matching A2's refusal to traverse links. |
| Mount root is the boundary | `..` above a mount lands in the machine's own filesystem, not the host's parent. |

## 4. Lifecycle

```text
start workload
      |
      v
host deadline expires          <- authoritative; classifies the result
      |
      v
destroy descendants, then parent
      |
      v
machine gone; nothing inside it runs
      |
      v
next execution gets a machine that never ran anything
```

The manager's own `--timeout` is set 5s *behind* the host deadline, so it is a
backstop rather than the classifier. This matters because the manager reports
its own deadline as exit 124, which a program is equally free to choose:
a workload that runs `exit 124` is reported `:completed` with `:exit 124`,
and a workload that outruns its deadline is reported `:timeout` with **no**
`:exit` key at all. Both are pinned by test.

The reaping proof is a workload that appends to a host-visible file five times a
second while its foreground command sleeps. At the deadline the file had grown;
four seconds later it had not grown further, and no machine was running.

## 4a. Resource bounds, and what each claim rests on

| Bound | Status |
|---|---|
| Wall-clock per execution | **Proven by adversarial test.** A workload that would never terminate was ended at the deadline, and a backgrounded process inside the machine stopped with it. |
| Machine memory | **Configured.** Each execution declares its allocation; the workload cannot exceed the machine. Not adversarially exhausted. |
| Disk / workspace | **Configured.** The writable layer is on the machine's own disk, sized per execution. Not adversarially filled. |
| Captured output | **Proven by test.** Both streams truncate at their budget and report the true byte count. |
| Host filesystem writes | **Proven by adversarial test.** The only host tree the machine can see is mounted read-only; a workload that deleted, overwrote and created files left the host byte-identical. |
| Network egress | **Proven by test, from inside.** No default route and a failed outbound attempt, asserted in the machine rather than read from configuration. |
| CPU | **Not independently bounded.** A machine gets a vCPU count, not a share or a quota. A workload can saturate the CPUs it was given for as long as its deadline allows. |
| Process count | **Not measured.** Nothing bounds how many processes a workload starts inside its own machine. |

The last two are the honest limits of this milestone. Neither is a containment
hole — both are bounded transitively by the wall-clock deadline and by machine
teardown — but neither is independently enforced, and the findings do not claim
they are.

## 5. What a result says

```clojure
{:status :completed          ; or :timeout, :worker-failure
 :exit 0                     ; present only when the workload actually exited
 :stdout "..." :stdout/bytes 220000 :stdout/truncated? true
 :stderr "..." :stderr/bytes 0      :stderr/truncated? false
 :duration-ms 996
 :worker/disposition :terminated
 :project/input-stable? true
 :project/input-coordinate "sha256:..."   ; absent when not stable
 :project/entry-count 102
 :project/bytes 858944}
```

`:stdout/bytes` is the true size, not the kept size. Reading continues past the
budget rather than stopping at it, because a workload whose output is not
consumed blocks on a full pipe — abandoning the stream would turn a chatty
command into a hang.

One defect the tests caught: the manager announces itself on stderr and has no
quiet flag, so its progress line was being reported as something the workload
wrote. It is removed, and its bytes removed from the count, so both describe the
workload alone.

## 6. Dogfood

The target is the bbagent repository itself, and the command is
`bb script/a3a-source-check.clj` — a babashka script the project keeps, checking
two invariants the project actually has: that every namespace matches its path,
and that every test namespace is registered in the runner.

The second is not a hypothetical. This repository violated it while A3a was
being written: two new test namespaces existed, passed in isolation, and were
not part of `clojure -M:test` because nobody had added them to the runner. The
check was verified to fail when that condition is reintroduced.

Babashka was chosen over `clojure -M:test` deliberately. It needs no project
dependencies and no network, so the worker runs it against the real working tree
with nothing mounted but the project and a single static binary. Running
bbagent's own JVM suite would have required mounting a JDK and `~/.m2` — the
latter a credential-adjacent tree — which is in tension with what the milestone
is proving. See the nonclaims.

Against the real repository: 102 entries, 858,944 bytes, check passed in 996ms.
In the same worker a second command then ran `rm -rf src/bbagent`, overwrote
`README.md`, and created `WORKER-WAS-HERE.txt`. It believed it succeeded. The
host checkout's input coordinate was unchanged, `src/bbagent/worker.clj` was
still present, `WORKER-WAS-HERE.txt` did not exist, and `git status` was
byte-identical to before the run.

## 7. Recommendation for A3b

Expose one primitive, not a vocabulary:

```clojure
(project/run {:argv ["bb" "test"] :cwd "." :timeout-ms 120000})
```

rather than `project/test`, because testing and building are project-specific
procedures that should compose in SCI over a primitive, not accumulate in the
trusted host catalog.

It needs a **new profile** — `:agent/project-execute` — not a widened
`:agent/project-develop`, which is now frozen.

It must declare an effect and be classified in `bb4t.catalog/effects`.
`:actuation` is the correct classification: a run can change the world, and
A2's machinery will then refuse to re-run it during recovery, so

```clojure
(def verification (project/run {...}))
```

resumes from its receipt rather than re-executing. A2 already fails closed on an
actuation without a receipt, so an A3b operation inherits the right behaviour by
being classified rather than by new recovery code. The result shape in section 5
is already inert data suitable for a receipt.

## 8. Blockers

None.

## 9. Nonclaims

- Linux x86_64 with KVM only. No claim about macOS, Windows, aarch64, or a host
  without `/dev/kvm`.
- The dogfood is a babashka project check, not bbagent's own JVM suite. Running
  the JVM suite in the worker would need a JDK and `~/.m2` mounted read-only;
  that was not done, so nothing is claimed about it.
- CPU is **configured, not independently bounded**: the machine gets a vCPU
  count, not a share or quota. Memory is bounded by the machine's allocation and
  wall-clock by the host deadline.
- The input coordinate detects that a project moved under a run; it does not
  prevent the workload from having seen the move, and it is not a transaction.
- Isolation rests on the hypervisor and on the read-only mount. No claim is made
  about a malicious guest kernel, container equivalence, or multi-tenant
  security. This is a single-player local worker boundary.
- The machine manager is an external dependency at a specific version. Its
  behaviour was measured, not proven, and a different version may differ —
  particularly the process-tree shape the reaping depends on.
- Truncating a stream at a byte budget can split a UTF-8 sequence; the kept text
  may end in a replacement character. The reported byte count is unaffected.
- No claim about package installation, long-running services, or background
  processes. A3a runs one command to completion or to its deadline.
