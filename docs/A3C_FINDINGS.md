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

## 4. What the gates caught that reasoning did not

Three defects, all found by tests rather than by thinking, all in the ten lines
around the privilege drop.

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

The privilege gates in particular: not root, runs as the project's owner,
`CapEff` and `CapPrm` both zero, cannot unmask `/input`, cannot mount anything,
no setuid binaries present, guest directories not writable, and the workspace
still fully writable — that last one matters, because a privilege drop that
broke the workspace would have traded one property for another.

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
