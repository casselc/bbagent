# A2 Findings (in progress): Useful Semantic Project World

Status: **A2 active.** `project/list` and `project/search` are delivered and
dogfooded. `project/edit` and `project/test` are not started.

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

- one model, one endpoint, three repetitions per arm. Directional, not a
  benchmark. The 0/3-versus-3/3 completeness difference is large enough to read
  through the noise; the attempts means are not a result;
- the self-dogfood is a single session per configuration, not a repeated
  measurement. It is evidence that the defects were real and that the fixes
  changed behaviour, not a quantified improvement;
- **no native or PTY evidence for any A2 change.** The bb4t changes touch value
  description and the allow-list, not the build, but that is an inference until
  the native build runs;
- the expanded `base-allow` is argued pure and corpus-checked, not proved. The
  corpus tests the denials that exist; it cannot show that no pure-looking
  symbol has a path to authority;
- `project/search`, `project/edit`, and `project/test` do not exist, so nothing
  here shows whether the composable-capability thesis holds for tasks that
  change the world rather than observe it;
- `concludes-limitation?` in the A1.1 harness now measures the wrong thing: it
  scored the right answer while enumeration was missing. The A2 harness measures
  answer completeness against known ground truth instead.

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

## 9. Next

`project/edit` with version-anchored mutation, where the interesting question is
conflict semantics rather than the edit itself. Then `project/test`.

Two smaller items the dogfood surfaced and did not fix: a broad search returns
matches from `artifacts/` and `docs/` that crowd out source, which is an
argument for the model narrowing with `:path` rather than for a host-side
default; and `project/read` still previews only 2,048 characters of a large
file, so the model reads a big source file in slices.
