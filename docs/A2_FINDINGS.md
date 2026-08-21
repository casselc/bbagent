# A2 Findings: Useful Semantic Project World

**Status: A2 complete. Recommended verdict PASS.**

## 0. For review

Four capabilities are delivered, dogfooded against a live model, and proved in a
native image: `project/list`, `project/search`, `project/stat`, `project/edit`.
The blocking defect that held the milestone open — a session that changed the
project could not be resumed — is closed, and the fix is a change to what
recovery *is* rather than a special case for the operation that exposed it.

### The blocking defect, and what closed it

Recovery rebuilt a session's SCI state by re-running its forms. That is exact
for computation and wrong for anything that touched a project. `project/edit`
made it visible — version anchoring makes re-application a conflict by design,
so a form recorded `:ok` replayed `:error` — but it was never only about edits:

```clojure
(def x (project/read "foo"))
```

was also wrong to replay, because if `foo` changed while bbagent was stopped,
`x` would silently resume holding today's contents rather than what the session
computed.

Each evaluation now records the semantic operations it invoked, and recovery
re-executes the Clojure while substituting those recordings at the operation
boundary. Section 10 states the design; section 13 states the evidence.

### What the evidence covers

| Evidence | Result |
|---|---|
| bbagent deterministic suite | 149 tests, 1170 assertions, 0 failures |
| bb4t deterministic suite | 25 tests, 198 assertions, 0 failures, incl. 96-case authority corpus |
| Native image | built at bb4t `cc18ca7b` / bbagent `c23b3163`; A2 grants present, 35 authority negatives denied, `:projected-class-count 0` |
| Native replay gate | a session that observed and changed a project resumes with neither repeated, gated by `script/build-native` |
| PTY proof | 37 gates pass, 7 of them new, including resuming a session that edited |
| Live model, fixture comparison | complete answers 0/3 before `project/list`, 3/3 after |
| Live model, this repository | orientation question 13→6 actions; budget question 4 actions; edit task 8→5 actions |
| Live model, edit → exit → resume → continue | 6 forms reconstructed exactly across a process boundary; edited file byte-identical |

### Decisions this needs

1. **Is the operation-boundary model the right recovery semantics?** It is now
   what the product does, and section 10 argues why the alternatives are worse.
   The nonclaim to weigh is that a capability declaring no effects is *assumed*
   pure, which the catalog states and validates classification against but does
   not prove.
2. **Is `:agent/project-develop` the right default?** Sessions default to a
   profile that can write. `:agent/project-survey` remains read-only and
   `:agent/project-read` remains the frozen A0 surface. No implementation
   evidence emerged against the default.
3. **Should `project/test` follow, or should A2 close here?** The milestone
   question is answered for observation, for change, and now for durability.
4. **Is compare-and-swap by observation good enough?** `project/edit` reads,
   compares, then renames; a writer inside that window is not detected. Kept
   deliberately for this single-player milestone.

### What changed that a reviewer should not have to discover

- recovery no longer re-executes semantic operations, and the durable journal
  carries an operation transcript per evaluation (section 10);
- `bb4t.catalog` now classifies every effect as an observation or an actuation,
  and an unclassified effect fails catalog validation;
- `bbagent.store` gained `result-event`, mirroring `request-event`;
- the base system prompt no longer enumerates absent authority, which the A1.1
  review recorded as an A2 entry condition (section 12);
- the default orientation is `:derived`, not the `:grounded` that A1.1 measured;
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

Current as of the recovery repair; supersedes anything narrower said earlier.
Section 13 adds the nonclaims specific to that repair.

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
- **a capability declaring no effects is assumed to be a pure function of its
  arguments.** The catalog states that contract and validates that every
  declared effect is classified as an observation or an actuation; it does not
  prove that an unannotated operation reaches nothing;
- **an operation result the journal's secret-stripping alters cannot be
  reconstructed,** so such a session fails closed on resume rather than
  resuming with a different value. Only `data.json/read` can produce one today;
- **a checkpoint still rewrites every form's source,** so a long session's
  journal remains quadratic in source bytes. Receipts were kept out of the
  checkpoint to avoid making that worse, not to fix it;
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
the coordinates. The gate and image numbers here are those of the survey
surface as it stood; sections 11 and 13 record the images that followed.

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

## 10. The blocking defect, and the recovery semantics that closed it

Found by the PTY proof, and it is the most important result in this milestone.

Resuming a session that had edited a file failed:

```text
A checkpoint form replay changed status
{:source "(project/edit {:path \"native-proof.txt\" :base :absent :content \"one\"})"
 :expected-status :ok
 :actual-status :error}
```

Computational replay rebuilt SCI state by **re-running the session's forms**,
which assumes they can be run again. `project/edit` cannot: version anchoring
makes re-application a conflict *by design*, so a form recorded `:ok` replayed
`:error` and recovery refused the session.

Both halves were behaving correctly and the combination was unusable. Failing
closed was right — not silent corruption, not a double write — but a durable
session that changed a file was unresumable, and durability is the product.

### It was never only about edits

The tempting fix is to special-case `project/edit`. That would have been wrong,
because the same defect is already present in a read:

```clojure
(def x (project/read "config.edn"))
```

Re-running this on resume binds `x` to whatever `config.edn` says *now*. If the
file changed while bbagent was stopped, the session silently resumes holding a
value it never computed, and nothing anywhere says so. That is worse than the
edit case, which at least failed loudly.

The general statement is that **replay is not re-execution**. It is
reconstruction of what a session computed. Those coincide exactly when a form is
a function of its own inputs, and diverge the moment it observes or changes
something outside itself.

### What was built

Record and replay at the **semantic operation boundary**, which is the one place
that knows which is which:

```text
replay original Clojure/SCI source
        │
        ├── ordinary pure Clojure executes normally
        │
        └── world-touching semantic operations
                   ↓
             return recorded historical result
             instead of re-observing/re-actuating
```

Nothing inspects the source. `defn`, `let`, branches, helpers defined in earlier
forms and several operations inside one form all re-execute as ordinary Clojure;
only the leaf calls are substituted. The example the design was written against:

```clojure
(defn bump [path]
  (let [before (project/stat path)
        text   (project/read path)]
    (project/edit {:path path
                   :base {:digest (:digest before)}
                   :content (str text "!")})))

(def bumped (bump "notes.txt"))
```

On resume, `bump` is redefined by running its `defn`, `bumped` is recomputed by
running `bump` — and the `stat`, the `read` and the `edit` inside it return
their receipts. The file is not read again and is not written again.

### The transcript

During evaluation, `bb4t.kernel/invoke-authorized` records one receipt per
invocation, in order:

```clojure
{:operation/id  :project/edit
 :capability/id :project/edit
 :effects       #{:project/write}
 :args/digest   "sha256:..."
 :status        :ok
 :result        {:path "notes.txt" :bytes 5 :digest "sha256:..."}
 :result/digest "sha256:..."}
```

Four decisions in that shape are worth stating.

**The caller owns the transcript.** `bb4t.context/evaluate` takes a handle the
caller made and can read back afterwards, so the receipts survive a *failed*
evaluation. That matters: `(do (project/edit ...) (/ 1 0))` changed the project
and then failed, and without its receipt the replay would attempt the edit a
second time.

**Every operation is recorded, not only effectful ones.** The invariant then
holds by construction rather than by trusting the effect annotation to be
complete, and the ordering check covers every call. `:effects` is still carried
on each receipt, because it is the metadata that decides what a *legacy* form
may do (below).

**Results are digested before the journal touches them.** The journal rewrites
large strings as content references and strips entries that look like
credentials. The first is lossless and the second is not, so the coordinate is
taken at the boundary; a result that came back changed fails closed rather than
being reconstructed wrong.

**Argument digests are total.** `bb4t.canonical/lenient-coordinate` never
rejects. A caller can legitimately write `(project/read 1.5)`, and the call that
refuses it still has to be identifiable in a transcript.

### Storage: the existing machinery, and one thing it could not carry

Receipts are journalled as `:repl/operations` on the `:repl/result` event, so
`store/externalize` blobs a large `project/read` result through the existing CAS
with no second persistence mechanism. A 78 KB read survives a restart and
replays as the whole historical string, not a preview of it.

They are deliberately **not** in the checkpoint. `checkpoint!` rewrites the
entire replay-form vector on every evaluation, so a form's recorded results
would be copied once more into every checkpoint after it — quadratic in the
bytes those results occupy, which for a few hundred forms of listings is
hundreds of megabytes. Checkpoint entries carry the `:request/id` instead and
recovery finds the receipts through `store/result-event`, a bounded lookup
mirroring the existing `request-event`. The pre-existing quadratic growth in
*source* bytes is untouched and remains a nonclaim.

### Fail closed, and check the transcript before the status

Replay refuses on operation mismatch, argument mismatch, a transcript consumed
too far, a transcript not consumed far enough, a result that did not survive
storage, a result too opaque to reconstruct, and a legacy form that would
actuate.

The ordering matters more than it looks. Consider a replay that diverges to
`(project/read "absent.txt")` where the session recorded
`(project/read "missing.txt")`. Both fail. A status check alone passes it and
the session resumes on a reconstruction that is not its history. The transcript
is what notices, so it is checked first. That case is pinned by test.

### Sessions recorded before receipts existed

A0, A1 and A1.1 sessions have no transcript, and pretending otherwise would be
exactly the dishonesty this repair exists to remove. They replay in a third
mode, decided by capability metadata rather than by a list of operation names:

- an **observation** effect re-executes against the live world, and is counted;
- an **actuation** effect fails closed, because there is no receipt saying the
  change was already made;
- the resume event records `:exact? false` with which operations were
  re-observed.

So an A0 read-only session still resumes exactly as it always did, and says
plainly that its reconstruction consulted today's project. `bb4t.catalog/effects`
carries the classification and `validate-catalog` rejects a capability that
declares an unclassified effect, so a future capability cannot be added and then
silently re-executed during recovery.

### The options that were not taken

**B. Skip effectful forms and leave the binding unbound.** Cheaper, and it makes
resume lossy in a way the operator has to notice and work around. Rejected: it
answers "can this session be resumed" with "partly".

**C. Snapshot SCI state instead of replaying forms.** Removes the class of
problem rather than this instance, and is a checkpoint redesign. Out of scope
here, and still available as its own milestone.

## 11. Native and PTY evidence for the write surface

Thirty gates passed against an image built at bb4t `f3547d02` and bbagent
`1149e0d5`. Superseded by section 13, which records 37 gates against the image
that carries the recovery repair; this section is kept for the harness findings
below, which are about how the proof was built rather than about that image. In
that image `:project-edit-anchored :ok` and `:project-edit-conflict-refused :ok`
sat alongside all 35 authority negatives with `:projected-class-count 0`.

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

## 12. Product claims reconciled with runtime behaviour

Three statements in the product were false against what it actually does. All
three are the same failure — prose asserting a capability boundary that code had
moved — and A1.1 recorded it as an A2 entry condition after finding absent
authority hardcoded in three places.

**The static system prompt denied editing authority the default profile grants.**
`resources/bbagent/system.txt` said *"You have no editing, shell, process,
network, or host API authority in the REPL"* while `:agent/project-develop`,
the session default, projects `project/edit`. It now states closure rather than
absence — the projected operations are the whole authority, nothing outside them
is reachable — which is capability-independent and cannot be falsified by
granting anything. What the model can specifically do is left to the `:derived`
orientation, which generates it from the Context's own description.

That prompt is the digest anchor for the recorded A1.1 coordinates, so the
change moves the anchor. That is the correct trade: A1.1's own review named this
sentence as the thing A2 would falsify, and the recorded A1.1 evidence keeps
naming the text it was measured against.

**`session/start!`'s docstring said the default profile was
`:agent/project-survey`.** The code has defaulted to `:agent/project-develop`
since the write surface landed. So did the CLI usage text. Both corrected.

**`CURRENT_SCOPE.md` listed `project/search` and `project/edit` as not
implemented.** Both shipped in this milestone. Reconciled.

## 13. Evidence for the recovery repair

Coordinates in `artifacts/a2-replay-evidence.edn`. Built by `script/build-native`
from bb4t `cc18ca7b` and bbagent `c23b3163`, producing a 75,565,312-byte image.
**No reachability metadata, build flag, or dependency was added**; recovery
semantics changed and the model's surface did not, which the 35 authority
negatives and `:projected-class-count 0` still hold alongside.

### Deterministic

bbagent 149 tests / 1170 assertions, bb4t 25 tests / 198 assertions, zero
failures. `test/bbagent/replay_test.clj` covers the acceptance cases: a pure
form; a `def` of a read whose file changed while the process was stopped; list,
search and stat against a changed world; an edit not issued twice; an edit's
result binding reconstructed; a helper containing stat, read and edit; a replay
that calls a different operation, calls one with different arguments, calls too
many, and calls too few; a receipt nothing consumed; a 78 KB read through CAS; a
session with no receipts at all. The observing and editing cases run against
both storage backends.

`a-session-that-edited-cannot-be-resumed-test` was the pin on the open defect.
It has become `a-session-that-edited-resumes-without-editing-again-test`, which
asserts the property rather than the failure.

### Native

`script/build-native` now gates on a replay scenario in the image: a session
reads `README.md` and applies an anchored appending edit, the README is
rewritten underneath it, and a second process resumes. The build fails unless
the file it wrote is unchanged, the reconstructed observation is the text the
session recorded, the *current* README text appears nowhere in the resumed
session, and the resume claims `:exact? true` with three forms reconstructed and
none legacy.

### PTY

37 gates, all passing. The write session is now **resumed at a terminal**, which
the previous proof deliberately did not attempt — its comment said so, because a
session that edited could not be resumed. Three new journal gates assert that
the edit's own result comes back (`REPLAYED-6`), that the file it wrote was not
written again (`UNCHANGED-twotwo`), and that the session recorded an exact
reconstruction.

### Live model

`script/a2-live-replay.clj`, one session, two processes, same model and endpoint
as the rest of the milestone. Asked to change `spacing` from 7 to 11 and to
retain the result, the model read the file, guessed `:base` as its content, was
refused, ran `project/stat`, applied the anchored edit, and retained it. The
process exited.

A second process resumed and was asked for a value it could only have from the
first process's computation:

```text
The value of `(:bytes applied)` is 137.

{:exact? true :forms 6 :legacy 0 :reconstructed 6 :reobserved []}
```

Six forms reconstructed, including both the failed edit and the successful one,
and the edited file byte-identical across the restart. That is the scenario the
milestone is about — stop for the day, pick the session back up — and it was
impossible before this repair.

### What is still not claimed

Added to section 6, and stated in the evidence artifact:

- **a capability declaring no effects is assumed pure.** The catalog states that
  contract and validates that every declared effect is classified; it does not
  prove that an unannotated operation is a function of its arguments;
- **a result the journal's secret-stripping alters cannot be reconstructed.**
  Such a session fails closed on resume rather than resuming with a different
  value. Only `data.json/read` can produce such a result today, and the choice
  between refusing and widening what the journal retains is a real one that this
  repair did not make;
- **checkpoints still rewrite every form's source.** Receipts were kept out of
  the checkpoint precisely to avoid making that worse; the pre-existing growth
  in source bytes is untouched;
- **one live repetition.** The live scenario demonstrates the path end to end.
  It is not a measurement.

## 14. Next

**A2 is closed.** The milestone question — can the model do real project work
through a small composable semantic capability set while retaining persistent
SCI as its interface — is answered for observation, for a version-anchored
change, and now for durability across a restart.

`project/test` is the natural next milestone, where the question is what counts
as verification rather than what counts as running a command. It was
deliberately excluded from this repair.

Two smaller items the dogfood surfaced and did not fix: a broad search returns
matches from `artifacts/` and `docs/` that crowd out source, which is an
argument for the model narrowing with `:path` rather than for a host-side
default; and `project/read` still previews only 2,048 characters of a large
file, so the model reads a big source file in slices.

One item this repair surfaced and did not fix: the checkpoint's quadratic
source growth, which is an argument for the state-snapshot design that section
10 declined to start here.
