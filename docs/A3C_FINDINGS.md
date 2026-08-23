# A3c Findings: a guest of our own, and a workload that is not root

## 0. For review

**Verdict: PASS, recommended.** The one property A3b declined to claim is now
claimable, and the argument that would have been the most dangerous to expose
has been deleted rather than guarded.

**What changed, in one line each**

| | A3b | A3c |
|---|---|---|
| workload identity | root | derived from the project's owner |
| capabilities | full | none — `CapEff: 0000000000000000` |
| can undo the hiding | **yes**, measured | **no** — `umount` and `mount` both refused |
| toolchain | a host directory bind-mounted `:ro` | inside the image; no host tool path exists |
| host paths mounted | project + tool directory | project only |
| guest identity | implicit, "whatever alpine smolvm pulled" | an image digest in the coordinate |
| prelude | shell source assembled on the host command line | in the image, covered by the digest |
| host/guest agreement | none needed | a contract version checked before any mount |

**The claim, stated exactly.** No excluded project path is reachable by the
workload, and the workload cannot make one reachable, because it has no
capability with which to unmount the mask and the guest carries no setuid
binary. This is defence in depth *behind* the machine, not a replacement for
it: a kernel bug would still get out, and the machine is still what the
isolation rests on.

**Decisions this needs from a reviewer**

1. **A root-owned project is refused.** There is no unprivileged identity to
   derive from it, and running it privileged would silently give back what
   this milestone bought. An override exists and is recorded.
2. **Commands needing root stop working** — `apk add`, ports below 1024.
   Package installation was already excluded.
3. **The build gains a second artifact.** `script/build-native` now builds the
   guest image from the same pinned checkout as the binary and gates on its
   digest appearing in the executor's description.

---

## 1. Why the image, and not just a privilege drop

Dropping privilege alone would have closed the stated gap. Three things made
the image worth the extra moving parts, and only one of them is about
privilege.

**The tool directory was the sharpest edge in A3b.** `bbagent.worker` accepted
an absolute host path and mounted it read-only into the guest. That was safe
only because its caller was trusted host code; A3b's findings said plainly
that a model able to supply `/home/user/.ssh` as a "tool path" would have a
host-read authority path the machine boundary does nothing about, because the
read happens on the host side of it. A3c does not guard that argument — it
removes it. The toolchain is in the image, and `bbagent.worker` no longer has
a parameter that names a host directory for anything but the project.

**The guest was implicit.** A3b's coordinate said `smolvm 1.7.5` and nothing
about what that manager was running. Two runs a month apart could have used
different alpine images and produced the same coordinate. Now the image digest
is in `:executor/guest`, which is inside the context coordinate, so a session
recorded against one guest cannot be silently resumed against another.

**The prelude was host-assembled shell.** It was carefully written — positional
parameters, no interpolation — but it was still a program the host built as a
string and handed to a shell. It now lives in the image, so one digest covers
the toolchain, the mount sequence, the hiding, and the privilege drop together.

## 2. The shape

```
smolvm machine run --image <archive> -v <project>:/input:ro
  -- /usr/local/bin/bbagent-prelude 1 1000:1000 2 .git deep/target . bb check.clj
                                    ▲  ▲        ▲                   ▲  ▲
                            contract  uid:gid   hidden paths      cwd  argv
```

The host passes data. Every decision — how the workspace is built, what is
hidden, when privilege is given up — is in the image. Inside, in order:

1. `mkdir` the workspace, and **chown the overlay's upper layer** to the target
   identity. Without this the merged workspace root belongs to root and the
   workload cannot create anything at the top of its own project.
2. mount the overlay, lower = the read-only project.
3. `rm -rf` each hidden path, which writes whiteouts into the upper layer.
4. mask `/input` with an empty tmpfs.
5. `cd`, then drop.

Everything above step 5 needs `CAP_SYS_ADMIN`. Nothing below it does.

## 3. Identity comes from the project

The workspace is an overlay whose lower layer is the project, so its
permissions are the project's permissions. A workload running as the wrong uid
can read world-readable files and cannot modify the ones it is supposed to own:
overlayfs copy-up needs write permission on the merged file, and the merged
file's mode and owner come from the host.

So the identity is derived, not chosen and not baked into the image — an image
with a fixed uid would work only for projects owned by that uid. `bbagent.executor`
reads the project root's `unix:uid`/`unix:gid` for **each run**, from the
project bb4t named, so it is always the identity matching the tree actually
being executed against. A session may preflight it at creation so that a
session which could never run anything fails when it is made.

A root-owned project has no unprivileged identity to derive. It is refused.

## 4. Defects discovered during closure

Five, all found by tests or by a build rather than by thinking. Three are in
the ten lines around the privilege drop; two are about how evidence is checked.

**`su-exec` erases "command not found".** It exits 1 when it cannot exec, which
is indistinguishable from a program that ran and chose 1. A3a's whole exit-code
discipline — 125 for "did not run", the workload's own status otherwise — was
quietly broken by it. The drop now goes through a shell, which still reports
127. Caught by an existing A3a test, which is the argument for keeping old
tests running against new substrate.

**`su-exec` resets `HOME`.** It takes it from the passwd file, and the derived
uid has no passwd entry, so `HOME` became `/` — a filesystem the workload
cannot write. Every tool that writes to `HOME` would have failed in a way that
looked like the tool's fault. `HOME` is now set on the far side of the drop.
Caught by the A3a environment gate.

**busybox `setpriv` cannot change uid, and hardening can delete `mount`.** The
first design used `setpriv --reuid`, which worked because smolvm's default
guest happens to ship util-linux's. A custom alpine image has busybox's, which
silently lacks the option. Installing `util-linux` to fix that replaces
busybox's `mount` with a *setuid* binary — so the "strip every setuid binary"
hardening step deleted `mount` and broke the prelude. `su-exec` avoids both and
drops capabilities implicitly on the uid change. The image build now asserts
there is no setuid or setgid binary, so this cannot regress silently.

**A renamed gate key stopped a build.** `:version/tool-bundle-required` became
`:version/guest-image-required` when the tool bundle became an image, and
`script/build-native` was still grepping the old name. The build stopped on a
gate that was wrong rather than on behaviour that was — the second time this
class of defect has cost a build.

**Half the gate patterns could not be checked outside a build.** The A3b and
A3c phases already printed identically on the JVM and in the image, because A3b
fixed that. The A3a phases still used plain `prn`, whose namespace-map
abbreviation depends on `*print-namespace-maps*` — bound by `clojure.main`,
unbound in a native image — so their patterns matched the image and could not
be verified anywhere else. All evidence phases now go through one emitter, and
closure verified every one of the **94** positive gate patterns in
`build-native` by extracting them mechanically from the script and matching
them against freshly generated output, plus the four negative leak checks by
their absence. Checking only the patterns just written is what let the stale
one through.

## 5. Evidence

Every A3a and A3b gate was re-run **unchanged** against the new substrate. That
is the main evidence that A3c changed the guest and not the semantics.

| | Measured |
|---|---|
| A3c gates | 29 — guest description 6, privilege 8, image 7, contract 3, dogfood 4, plus the image digest appearing in the description |
| A3a gates, re-run | 13 isolation + 6 lifecycle + 4 dogfood |
| A3b gates, re-run | 5 compatibility + 8 authority + 14 isolation + 5 unstable-input + 7 replay + 4 dogfood |
| authority negatives | 61 on the default profile (was 58) |
| deterministic suites | bbagent 219 tests / 1425 assertions, bb4t 25 / 199, 0 failures |
| gate patterns | 94/94 positive patterns in `build-native` matched against real output, 4/4 negative leak checks absent |
| live dogfood | 3/3 arms finished; verification works unprivileged, and a destructive workload leaves the checkout intact |

The privilege gates in particular: not root, runs as the project's owner,
`CapEff` and `CapPrm` both zero, cannot unmask `/input`, cannot mount anything,
no setuid binaries present, guest directories not writable, and the workspace
still fully writable — that last one matters, because a privilege drop that
broke the workspace would have traded one property for another.

## 5a. The live dogfood, through the public semantic path

A local Qwen3.6-27B under `:agent/project-execute`, against the hardened guest,
one throwaway fixture per arm. The path exercised is the whole one: bounded SCI
→ `project/run` → pinned image → unprivileged workload → a real project check.

| arm | result |
|---|---|
| verify | finished in 12 actions; 2 executions, 0 refused, **both anchored**; saw its check pass; the fixture ended fixed |
| resume | finished; session thrown away and rebuilt — 12 forms reconstructed, 0 re-observed, executor invocations 2 before and 2 after, **no second execution** |
| unstable | finished; the host rewrote the project throughout the turn and **both** runs came back `:project-changed` with no exit and no coordinate |

The mutation half was proven separately, against a throwaway clone of this
repository rather than the checkout itself. A workload that ran `rm -rf
src/bbagent`, overwrote `README.md` and created a new file believed all three
succeeded — `:dogfood/vandal-believed-it-succeeded :ok` — and the clone came
back with `src/bbagent/worker.clj` present, no new file, and a `README.md` that
still begins `# bbagent`. The same phase ran the project's real babashka check
to completion **as the unprivileged workload**, exit 0, from the toolchain
inside the image.

That is the A3c claim end to end: an unprivileged workload can do real project
verification, and can do anything it likes to its workspace without any of it
reaching the authoritative checkout.

## 6. Nonclaims

- **The machine is still the boundary.** This is defence in depth behind it.
- **Not proof against a kernel bug.** It is proof against `umount`.
- **The image is trusted, not verified.** Its digest says which bytes ran, not
  that they are benign. It is built from a Containerfile in this repository
  from the same pinned checkout as the binary, which is provenance, not safety.
- **The overlay's lower layer is still live.** A3a's finding stands; A3b's
  `:project-changed` is still how a moved project is reported.
- **A root-owned project is refused, not handled.** That is a real limitation
  for anyone whose checkout is root-owned.

## 7. An operational note

`bbagent.sqlite/authority-smoke!` proves the anchored-write path by performing
a real anchored write on the `README.md` of whatever project it is handed, and
the A3a/A3b/A3c dogfood phases run a workload that tries to destroy the project
they are given. The native build hands all of them throwaway fixtures. During
A3c I ran `authority-smoke!` against this checkout to read a probe count out of
its result, which appended to this repository's own README twice, one of which
reached `830ec07`. Both lines are removed and the hazard is recorded in
`AGENTS.md`, because the problem is not the line — it is that several evidence
functions actuate and their names do not say so at the call site.

## 8. Exact coordinates

| | |
|---|---|
| bbagent runtime, gates, suites, dogfood | `740d117457aae6d84bbe3f9dc713bea1c894ea8c` |
| bb4t | `227d38542d76565c2e3ac64d0c682141b1d597b9` — **unchanged since A3b** |
| native image | `sha256:eb4d9ce0…`, 76 876 032 bytes |
| guest image archive | `sha256:f5ac5ff2…`, 148 299 264 bytes, built from the same commit |
| GraalVM | Oracle GraalVM 25.2.4+7.1 (25.0.4+7-LTS-jvmci-25.2-b20) |
| machine manager | smolvm 1.7.5 |
| toolchain in guest | babashka v1.13.219 |

`artifacts/a3c-evidence.edn` is the authoritative record; any figure quoted in
prose is quoting that file.

**bb4t has no A3c implementation change.** `git diff 227d3854..HEAD -- src/` is
empty. A3c is entirely below the bb4t seam: bb4t still knows only that a Context
may be granted an authorized execution environment, and still does not know a
machine exists. A `bb4t-a3c` tag is placed at that unchanged coordinate so the
milestone has a bb4t coordinate to name, following the A3a precedent; no commit
was manufactured to obtain a new SHA.

**A3b was not tagged when A3c was frozen.** It had been recommended PASS and
stopped for review, and tagging was never requested, so freezing A3c left a gap
in the tag sequence. That is what `artifacts/a3c-evidence.edn` records, and it
was true at the time.

> **Later note.** A3b has since been frozen retroactively at its own evidence
> coordinate, `bbagent-a3b` / `bb4t-a3b` = `861bea5` / `227d3854`. The sequence
> is now continuous. The A3c evidence artifact is left as measured rather than
> amended, because an evidence record that drifts after its freeze is worth
> less than one that is occasionally out of date; `docs/CURRENT_SCOPE.md` is
> the current statement.

## 9. Recommendation

**PASS.** Every item on the closure checklist has evidence:

- source and evidence agree; A3b's records are annotated, not rewritten
- workload is non-root by default, uid/gid derived from the project owner
- `CapEff` and `CapPrm` both zero
- a root-owned project fails closed by default
- the workload cannot unmask `/input` and cannot mount anything
- the workspace remains writable and supports ordinary project mutation
- the guest image digest participates in the executor's identity
- a wrong pinned digest and a missing archive are both refused
- a prelude contract mismatch refuses before argv runs
- the toolchain comes from the image; no host tool directory is mounted
- A3a's and A3b's gates pass unchanged
- `project/run` replay still causes no second execution
- the authority corpus passes at 61 probes
- deterministic suites and the native product proof pass, 37/37 PTY gates
- the live dogfood succeeds on all three arms, and a destructive workload
  leaves the checkout intact

A3c strengthens the **guest** boundary. It does not replace SmolVM as the hard
host-isolation boundary, and nothing here should be read as claiming it does.
