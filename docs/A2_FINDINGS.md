# A2 Findings (in progress): Useful Semantic Project World

**Status: A2 active, blocked on one defect. Not ready to accept.**

## 0. For review

Four capabilities are delivered, dogfooded against a live model, and proved in a
native image: `project/list`, `project/search`, `project/stat`, `project/edit`.
The milestone question is answered in the affirmative for observation and for a
single anchored change. It is **not** answered for durable work, because of one
defect.

### The blocking defect

**A session that edits a file cannot be resumed.** Replay rebuilds SCI state by
re-running the session's forms; `project/edit` cannot be re-run, because version
anchoring makes re-application a conflict by design. A form recorded `:ok`
replays `:error` and recovery refuses the session. Both halves are correct and
the combination is unusable. Detail and options in section 10.

### What the evidence covers

| Evidence | Result |
|---|---|
| bbagent deterministic suite | 141 tests, 1013 assertions, 0 failures |
| bb4t deterministic suite | 18 tests, 167 assertions, 0 failures, incl. 96-case authority corpus |
| Native image | built; A2 grants present, 35 authority negatives denied, `:projected-class-count 0` |
| PTY proof | 30 gates pass |
| Live model, fixture comparison | complete answers 0/3 before `project/list`, 3/3 after |
| Live model, this repository | orientation question 13→6 actions; budget question 4 actions; edit task 8→5 actions |

### Decisions this needs

1. **Fix effectful replay before anything else?** Recommended. Section 10 states
   the shape of the fix; it is a recovery-semantics change, not a capability
   change.
2. **Is `:agent/project-develop` the right default?** Sessions now default to a
   profile that can write. `:agent/project-survey` remains read-only and
   `:agent/project-read` remains the frozen A0 surface.
3. **Should `project/test` follow, or should A2 close after the fix?** The
   milestone question is arguably already answered for the read surface.
4. **Is compare-and-swap by observation good enough?** `project/edit` reads,
   compares, then renames; a writer inside that window is not detected.

### What changed that a reviewer should not have to discover

- the default orientation is now `:derived`, not the `:grounded` that A1.1
  measured, because granting `project/list` made `:grounded`'s prose false;
- the per-turn action budget moved from 12 to 40;
- `base-allow` grew from 26 symbols to a reviewed pure set including `fn` and
  `defn`, argued pure and corpus-checked rather than proved (section 6);
- failure messages now reach the model, filtered by an allowlist;
- sessions default to a profile with write authority.

## 1. The question

> Can the model solve normal project-development tasks through a small,
> composable semantic capability set while retaining persistent SCI as its
> primary working interface?

A1.1 supplied the entry signal: the model discovered its surface and correctly
reported that directory enumeration was the capability it lacked. `project/list`
answers that specific report.

## 2. Measured: does granting `project/list` change the answer?

Three repetitions per arm, arms alternated by repetition so run order does not
load onto one, same model and endpoint as A1.1 (`Qwen3.6-27B-MTP-GGUF`,
Lemonade loopback), same prompt that failed in A1:

> *"What files does this project contain, and what does each one do?"*

The fixture is built to the requirement the A1.1 review derived: **no file
enumerates the others, and no filename is guessable.** A1.1's fixture had a
README naming the only other file, which let the model reconstruct the answer
without enumerating and look accidentally correct. Ground truth is four files,
two of them (`tools/emit_manifest.clj`, `config/thresholds.edn`) unreachable by
guesswork.

| Arm | Finished | Used `project/list` | Complete answer | Attempts | Errors |
|---|---:|---:|---:|---:|---:|
| before — A1.1 surface, `:grounded` | 3/3 | 0/3 | **0/3** | 2.7 | 1.0 |
| after — A2 surface, `:derived` | 3/3 | **3/3** | **3/3** | 9.0 | **0.0** |

Every classification was read by hand, not taken from the substring match.

**Before** is the correct answer to an impossible request, three times: *"I
cannot enumerate directories, so I cannot list all files in the project."* One
run guessed `src/quarry.clj`, `project.clj`, and `deps.edn`, got errors, and
reported that honestly rather than asserting anything. `:grounded` did its job.

**After**, every run walked the tree — root, then each directory it found — read
every file, and produced a complete correct inventory including both unguessable
files. Zero REPL errors across all three runs: with the operation list and
arglists in front of it, the model never guessed a name.

The attempts increase is the work, not waste. The before-arm is cheap because it
gives up.

## 3. Dogfooding bbagent on bbagent

The measured comparison uses a fixture. The real test was pointing bbagent at
its own repository and asking a question with a right answer:

> *"Which orientation variants does this project support, and what is the
> difference between the grounded and derived ones? Cite the file you got this
> from."*

It found `src/bbagent/orientation.clj`, quoted the `modes` docstring accurately,
and explained the distinction correctly. It also exposed three defects, none of
which any unit test would have caught, because all three are about what the
model can do with a *correct* result.

### 3.1 The action budget was sized for a smaller surface

The first attempt navigated perfectly — root, `docs`, `src`, `src/bbagent` — and
hit the twelve-action limit *on the step that read the file it wanted*. A0 set
twelve for a surface whose only observation was reading one named file. Listing
changes the shape of a turn: orienting in a real repository costs a listing per
directory before any file is read. Raised to forty and made a parameter. The
limit guards against a model looping; it is not a statement about how much
looking a task deserves.

### 3.2 An oversized value reported its size and nothing else

`project/read` on a 9.4KB source file returned:

```clojure
{:value/kind :inert-data
 :value/type "java.lang.String"
 :value/encoded-characters 9797}
```

Not one character of content. The model read the file successfully and then
spent **nine of thirteen actions** trying to see it: `def` then deref, `pprint`,
`take`, `type`, `.substring`, and finally `println`, which worked only because
`:out` is returned in full. The escape hatch existed and was undiscoverable.

`bb4t.value/describe` now includes a bounded prefix of an oversized value,
marked `:value/truncated?`, with the true character count. A string previews as
its own text.

### 3.3 The bounded vocabulary could not compose

The model's entire Clojure vocabulary was **26 symbols**. No `fn`. No `defn`. No
`take`, `subs`, `filter`, `sort`, or `clojure.string`. It could call an
operation and print the result, and little else.

This is the product thesis failing quietly. The intended workflow is that the
agent writes `(defn related-tests [sym] ...)` and builds task-specific
vocabulary out of authorized primitives — which was not possible, because the
base language was absent. `base-allow` now carries a reviewed pure set. Every
symbol is pure: nothing that performs IO, resolves a Var by name, evaluates data
as code, touches a host class, mutates state, or reads the environment. The
96-case authority corpus passes unchanged, every denial intact.

### 3.4 Result

Same question, same repository, after 3.1–3.3:

| | Actions | Errors | Outcome |
|---|---:|---:|---|
| before | 13 | 5 | budget exhausted on the first attempt; answered on the second |
| after | **6** | **0** | answered directly, with the citation |

## 4. Authority

`project/list` is one directory deep by construction and refuses a symbolic link
as a traversal step **in both directions** — a link out of the root cannot
escape, and a link into the root is refused too, because a total rule is easier
to trust than a conditional one. Links are reported in listings as `:symlink`;
they are described, never followed. Tested: root listing, non-recursion,
absolute paths, `..` traversal, symlink refusal both ways, non-directories,
absent paths, malformed arguments, and that read authority does not imply
listing authority.

**A new capability got a new profile.** `:agent/project-read` is frozen and
still reproduces the recorded A0/A1/A1.1 surface exactly, pinned by a test that
also checks listing is unreachable from it. `:agent/project-survey` is the A2
surface. A session records its profile and **inherits it on resume**: resuming an
A0-era session into a wider surface would let a replayed form that once failed
now succeed, and recovery would fail its own status-equivalence check.

## 5. The orientation entry condition paid off immediately

The A1.1 review left A2 an entry condition: the model's limits were stated as
prose, including *"you cannot enumerate a directory"*, which would become false
the moment enumeration was granted. `:derived` was built to state closure
instead.

Granting `project/list` falsified `:grounded` exactly as predicted. `:derived`
picked up the new operation **with no prompt edit** and denied nothing the
context grants. The entry condition earned itself within one milestone, which is
the argument for treating a stale-prone constant as a defect rather than a note.

## 6. Nonclaims

Current as of `project/edit`; supersedes anything narrower said earlier.

- **one model, one endpoint, few repetitions.** Every live number here is
  directional. The fixture comparison is three repetitions per arm; the
  self-dogfoods are single sessions. The 0/3-versus-3/3 completeness difference
  is large enough to read through the noise. The action counts are not a
  benchmark, and one of them moved the wrong way once (section 7);
- **the expanded `base-allow` is argued pure and corpus-checked, not proved.**
  The 96-case authority corpus tests the denials that exist; it cannot show that
  no pure-looking symbol has a path to authority. This is the largest unproved
  claim in the milestone;
- **`project/edit` is compare-and-swap by observation, not atomically.** The
  digest is read, compared, and then the rename happens. A writer that changes
  the file inside that window is not detected;
- **no JVM/native suite parity.** The native evidence is the smoke, the
  authority corpus, and the PTY gates — not the 141-test suite;
- **Linux x86_64 glibc only.** No cross-platform or static-linking claim;
- **the image digest is an artifact identifier, not a reproducibility claim.**
  Two builds of identical source produced identical size, identical behaviour,
  and different digests (section 8);
- **no `project/test`,** so nothing here shows whether the composable-capability
  thesis holds for verification;
- **the PTY gates prove an operator can drive the interface,** not that the
  rendering is correct in every terminal. Semantic claims are asserted against
  the durable journal instead, for the reason in section 11;
- **`concludes-limitation?` in the A1.1 harness now measures the wrong thing.**
  It scored the right answer while enumeration was missing.

## 7. `project/search`

The second capability, motivated by the dogfood above: the model was listing
directory by directory to find one file.

Searching file contents for a regex, returning `{:path :line :text}` sorted by
path. It inherits `project/list`'s traversal rules — never follows a symbolic
link, skips dot-entries unless asked — and skips files that are not valid UTF-8,
which is how a binary file is recognized without guessing from its name.

### Matching is bounded, and the bound is measured

The obvious claim to make here is "this prevents catastrophic backtracking."
Measuring it first showed that claim would have been wrong. Java's matcher is
**not** exponential on the textbook cases:

| pattern | n | character reads |
|---|---:|---:|
| `(a+)+$` | 200 | 41,197 |
| `(a+)+$` | 400 | 162,397 |
| `(a+)+$` | 1000 | 1,005,997 |

That is quadratic, not `2^n`. So the per-line budget is not a defence against
exponential blowup the engine already avoids; it bounds the **superlinear**
case, where one long line and a nested quantifier still burn real CPU and a file
full of them multiplies it. Each line matches through a counting `CharSequence`
and fails past 200,000 reads, so search cost stays a function of project size
rather than of pattern cleverness. The test pins the measured boundary: 200 a's
finishes, 1000 does not.

### Measured effect

Asked *"Where is the per-turn action budget defined, what is its value, and why
was it set to that number?"*, against this repository:

```text
(project/list ".")
(project/search "budget")
(project/read "src/bbagent/agent.clj")
(project/read "docs/A2_FINDINGS.md")

4 actions, 0 errors
```

It quoted the docstring correctly. Search reached the file in one action.

### Two defects the previous change had introduced

Both found by using it, neither by a test.

**Lazy sequences described as opaque.** `take`, `map`, and `filter` all return
one, so expanding the vocabulary let the model compose and then *not see what it
had composed*. `(take 2 hits)` came back with no data at all.

The fix is not just "describe them". Realization runs the sequence's own
computation, so it has to happen **during evaluation** — inside the out/err
capture, before the success event — and not at description time, which runs
after the evaluation has already been recorded as succeeding and where printed
output would go nowhere. The BB1 corpus property protecting this was replaced
rather than deleted: `lazy-result-opaque?` became
`lazy-result-realized-as-data?` plus `lazy-result-bounded?`, and host objects
stay opaque.

**`str/` did not resolve.** It is how Clojure is written, and the bounded
context has no `require` with which to establish an alias. The dogfood watched
the model reach for `str/replace` and fail twice. SCI checks permission against
the symbol *as written*, before alias resolution, so an alias alone does not
make `str/join` callable — both spellings are listed. That keeps the allow-list
a literal statement of what may be written, which is the property an authority
list should have: no spelling permitted by indirection. An unlisted string
function is still denied under either name.

### Composition now works end to end

```clojure
(def hits (project/search "needle"))
(defn in-dir [ms d]
  (filter (fn [m] (str/starts-with? (:path m) d)) ms))
(count (in-dir hits "src"))
```

That is the product thesis running: a capability result refined by
agent-authored vocabulary, with no new host operation.

### Run-to-run variance is real

A second run of the *same* orientation question took 13 actions with 2 errors,
against 6 before search existed. A broad `(project/search "orient")` returned
matches in artifacts and docs, and the model followed them into an evidence file
and a filename that does not exist before finding the source. **Search does not
monotonically reduce work; it changes what the model has to filter.** Single
sessions are not a measurement, and this one is reported because it is
unflattering rather than despite it.

## 8. Native and PTY evidence

Previously the standing gap: every A2 claim was a JVM claim. It is now closed
for the capabilities delivered so far. `artifacts/a2-native-evidence.edn` holds
the coordinates.

Built by `script/build-native` from the local working repositories at bb4t
`05fd11a9` and bbagent `982bafc7`, producing a 74,909,952-byte image.

**A2 added no reachability metadata, no build flag, and no dependency.** That is
worth stating because A1 needed two native-image additions for JLine and charm,
both found as real failures. `project/list` and `project/search` use only
`java.nio.file` APIs the image already reached through `project/read`, and the
expanded `base-allow` is pure Clojure. The image grew by roughly the code added.

### Authority in the image

```clojure
:context/grants #{:project/read :project/list :project/search
                  :data/json-write :data/json-read}
:projected-class-count 0
:supplied-import-count 0
:negative-probe/count 35
:positive-probes {:core :ok :json-read :ok :json-write :ok
                  :project-read :ok :project-list :ok :project-search :ok
                  :composition :ok}
```

The native authority smoke previously exercised only the two operations A0
shipped with, so an image could have lost either new capability and the build
would still have passed. It now evaluates both plus a composition — `defn`,
`mapv` and `str/join` over a listing — and the build gates on all three. The
point is that positives and negatives hold **together**: the image gained the
project world and the model gained no class and no import.

### PTY: 25 gates, all passing

`script/tui-native-proof.py` drives the real executable in a real PTY. Eight
gates are new for A2: the capability pane shows both new operations because it
projects the real context description; the operator REPL calls `project/list`
and `project/search` natively; `defn` defines a helper; `str/` composes over a
capability result; a lazy result is readable; and on resume both the capability
pane and the **agent-authored helper** come back — a helper is computational
state, and replay has to reconstruct it exactly as it does a `def`.

### The gate found a defect the JVM suite could not

`native_lazy_visible` asserted that a listing's contents appear on screen. They
did not. The operator REPL pane rendered only the result *status*:

```text
repl> (take 1 (map :name (project/list "."))) => :ok
```

Every successful evaluation read `=> :ok` whatever it produced. That was
survivable while the bounded surface returned arithmetic; once a capability
returns a listing or search matches, the value is the entire reason for running
the form, and the operator could not see it. Now the pane summarizes the value,
bounded, with errors reporting their category and oversized values their preview
and size.

**This is the third consecutive milestone in which the native gate caught
something the deterministic suite did not.** A1 recorded two such defects; this
is the next. The pattern is that unit tests assert on values while these gates
assert on what a person can actually see and do.

### Reproducibility, and what an image digest is worth

The image was built twice from the same coordinates: once from the local
working repositories, and once from the published remotes after pushing. Same
size, identical gate results, identical positive and negative probes — and a
**different SHA-256**, differing across 56,469,352 bytes rather than in a
timestamp field.

So `native-image` output is not bit-reproducible in this toolchain. The digest
recorded in `artifacts/a2-native-evidence.edn` identifies *one artifact*; it is
not evidence about a source coordinate, and the artifact records that rather
than letting the digest imply more than it can carry. The reproducible parts are
the source coordinates and the gate results.

## 9. `project/stat` and `project/edit`

The first A2 capability that changes the world. The interesting part is not the
write but what it refuses.

An edit must state the version it believed:

```clojure
(project/edit {:path "src/quarry/lattice.clj"
               :base {:digest "sha256:..."}   ; from project/stat, or :absent
               :content "..."})
```

If the file changed since that digest, the edit is refused as a conflict rather
than applied. **There is no way to spell a blind overwrite** — omitting `:base`
is an error, not a default. That matters even in single-player, because the
human, an external editor, a formatter and a Git checkout all write to the same
world the agent is reading.

Writes go to a sibling temporary and are renamed, so a reader never observes a
partial file and a failed write leaves the original untouched. Traversal follows
`project/list`'s rules exactly.

**Not claimed:** this is compare-and-swap by *observation*, not an atomic one.
The digest is read, compared, and then the rename happens; a writer that changes
the file inside that window is not detected. For one operator and one agent that
is the honest boundary.

Write authority gets its own profile. `:agent/project-survey` stays read-only
and gains only `project/stat`, so a surveying context remains a meaningful thing
to grant; `:agent/project-develop` is the profile that can change the project,
and is now the session default.

### Dogfood: it made the change

Asked to change `spacing` from 7 to 11 in a fixture and *change nothing else*,
against the real capability set:

```text
(project/search "spacing")
(project/read "src/quarry/lattice.clj")
(project/edit {... :base "<the file's content>" ...})   ; refused
(project/stat "src/quarry/lattice.clj")
(project/edit {... :base {:digest "sha256:3fca4a4b..."} ...})
```

The file was changed correctly and nothing else was touched.

### The refusal was not teaching anything

The way it succeeded was the finding. The model guessed `:base` as the file's
*content* rather than a digest — a reasonable guess — and the refusal it
received said only `:bb4t-evaluation-failure` with an empty data map. The kernel
had authored a message for exactly that mistake and bbagent was **throwing it
away**: it kept `ex-data` and dropped `ex-message`, so every refusal in the
product reduced to its category. The model recovered by spending an action on
`(doc project/edit)`.

A second defect sat underneath: SCI rethrows an evaluation failure with its own
location data and keeps the original as a cause, so reading `ex-data` off the
caught exception had been finding SCI's map rather than the kernel's. `:bb4t/data`
was empty even before the message was dropped.

Both fixed. What reaches the model is an **allowlist rather than a filter**, so a
key added to a failure somewhere in the kernel stays invisible until someone
decides it is safe. A conflict now reports both the digest it expected and the
digest it found, so an agent can re-anchor and retry without a second `stat`.

Same task, before and after:

| | Actions | Errors | Result |
|---|---:|---:|---|
| before | 8 | 1 | correct, after a `doc` round-trip and a verifying re-read |
| after | **5** | 1 | correct, straight from the refusal to `stat` |

The one error is the same wrong guess both times. What changed is that the
refusal now tells the model what shape it should have used, instead of only that
something failed.

## 10. A blocking defect: a session that edits cannot be resumed

Found by the PTY proof, and it is the most important result in this milestone.

Resuming a session that had edited a file fails:

```text
A checkpoint form replay changed status
{:source "(project/edit {:path \"native-proof.txt\" :base :absent :content \"one\"})"
 :expected-status :ok
 :actual-status :error}
```

Computational replay rebuilds SCI state by **re-running the session's forms**,
which assumes they can be run again. `project/edit` cannot: version anchoring
makes re-application a conflict *by design*, so a form recorded `:ok` replays
`:error` and recovery refuses the session.

Both halves are behaving correctly and the combination is unusable. Failing
closed is right — it is not silent corruption and not a double write — but a
durable session that changed a file is unresumable, and durability is the
product. **A2 cannot be accepted with this open.**

### The fix, and the options

This is a change to recovery semantics rather than to either capability. The
journal already records each form's result, so the information needed is
present; the question is what recovery does with it.

**A. Reconstruct the result, do not re-execute.** For a form whose operation
declares a world effect, bind its recorded result instead of running it again.
Replay stops being "run the session again" and becomes "restore what the session
computed", which is what it was always for. Cost: recovery must know which
operations are effectful — the catalog already says so via `:effects` — and the
reconstructed value must be the inert recorded one, so a session cannot resume
holding a value the world no longer supports.

**B. Do not replay effectful forms at all, and mark the binding unavailable.**
Simpler and stricter: `applied` would be unbound after resume, and the agent
would have to re-observe. Cheaper to implement, worse to use, and it makes
resume lossy in a way the operator has to notice.

**C. Snapshot SCI state instead of replaying forms.** Removes the class of
problem rather than this instance, and is the largest change of the three. The
S0b debt notes already contemplate it; A2 is the wrong place to start it.

**Recommendation: A.** It preserves the existing model, uses evidence the
journal already holds, and keeps `:effects` meaningful — an operation that
declares a world effect is exactly one that must not be re-executed to rebuild
memory. B is the fallback if A proves awkward; C should be its own milestone if
it is ever wanted.

Deliberately not attempted at the end of a long change: it needs its own step
and its own evidence.

Pinned by `a-session-that-edited-cannot-be-resumed-test` so it cannot regress
silently or be forgotten while it is open.

## 11. Native and PTY evidence for the write surface

Thirty gates pass against an image built at bb4t `f3547d02` and bbagent
`c7afbb6e`. In the image, `:project-edit-anchored :ok` and
`:project-edit-conflict-refused :ok` sit alongside all 35 authority negatives
with `:projected-class-count 0`.

### The proof was measuring the wrong thing

Getting there took four failed runs, and every failure was in the harness rather
than the product. Recorded because the harness is evidence too:

1. **Gates asserted `":ok"` appeared on screen.** Once the REPL pane rendered
   values instead of statuses that string stopped appearing — and worse, the
   pane redraws several previous entries per keystroke, so a gate looking for
   `":ok"` could always have been satisfied by an older line. It would have
   passed whether or not the thing it named worked.
2. **A real newline inside a Python byte literal.** `:content "…\n"` sent an
   Enter keypress mid-form, submitting a half-typed expression and corrupting
   everything after it. The product then behaved *correctly* in a way that
   looked like a bug: with the first edit broken, the base was still current, so
   the "clobbered" edit legitimately applied.
3. **A reused fixture directory.** A previous run's edits had left `README.md`
   containing `clobbered`, so `project/search "fixture"` correctly found nothing.
4. **Screen-scraping as the instrument for semantic claims.** charm redraws
   incrementally: once a pane is full, a value line is rewritten in place rather
   than emitted contiguously, so markers plainly visible to a person never
   appear in the byte stream.

The fourth is the one that changed the design. **Semantic gates now read the
durable journal; the screen keeps interface gates.** The journal is what the
product is built on and is authoritative about what the bounded context
computed; a terminal can attest that the interface renders, accepts input, and
exits, and should not be asked for more. That split is why the proof now states
`project/edit conflict: file changed since it was read` as a fact rather than as
a string someone hoped to see.

I also fixed a pane-overflow guard while chasing this, then checked whether the
test I wrote for it failed against the old code. It did not — the pane never
overflowed. The guard is still worth keeping, but it explained nothing, and
saying so is the difference between a fix and a coincidence.

## 12. Next

**Effectful replay first** — section 10. `project/test` after that, where the
question is what counts as verification rather than what counts as running a
command.

Two smaller items the dogfood surfaced and did not fix: a broad search returns
matches from `artifacts/` and `docs/` that crowd out source, which is an
argument for the model narrowing with `:path` rather than for a host-side
default; and `project/read` still previews only 2,048 characters of a large
file, so the model reads a big source file in slices.
