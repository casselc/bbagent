# A1.1 Findings: Capability Orientation

## 1. Verdict

**Pass, with a result that changes the A2 question.**

Orienting the model to the discovery surface it already had works, and works
reliably. But the first three variants exposed something the milestone did not
anticipate: **telling the model what it can do fixed discovery and did not stop
it from making claims it could not support.** Better-oriented variants converted
a visible failure into a plausible-looking, unsupported answer.

A fourth variant that constrains *claims* rather than *operations* fixed that
completely and produced the target behaviour on every run.

No capability, grant, ContextSpec, or catalog entry changed. Orientation is a
projection of the authority description that already existed.

## 2. The Question

> Can the model reliably discover and use its currently granted semantic
> surface if bbagent explicitly orients it to `apropos`/`doc` and supplies a
> capability preamble derived from the actual Context description?

Motivation: in the A1 dogfood, asked what files a project contains, the model
spent eleven of twelve actions guessing nonexistent Vars and exhausted the
action budget, while the operator watched the correct three-Var surface on
screen. It never tried `(apropos "")` or `(doc project/read)`, both of which
already work.

## 3. Design

`bbagent.orientation` composes the system prompt the model receives. It is pure
and derives entirely from the context description, so orientation is one more
projection of a single authority description rather than parallel prose:

```text
RuntimeCatalog / ContextSpec
          |
   context description
          |
  +-------+--------+-----------------+
  |                |                 |
TUI capability  apropos / doc   model preamble
    pane                          (A1.1)
```

The preamble is composed **inside `session/start!`**, after the Context exists,
because a generated preamble is a projection of that Context's own description.
The composed prompt is what `:prompt/system-digest` digests, so the coordinate
identifies what the model actually received rather than the base prompt alone.
`:prompt/orientation` records the variant. Both are durable in the
`:session/started` event, so every run in this experiment is attributable from
its journal alone.

### Variants

| Variant | Content |
|---|---|
| `:none` | the A0/A1 system prompt, unchanged |
| `:minimal` | one instruction naming `apropos` and `doc` |
| `:generated` | the projected operations with the runtime's own arglists and docstrings, plus the minimal instruction |
| `:grounded` | `:generated` plus a constraint on what may be *asserted* |

`:grounded` was added **after** the first three ran, in response to what they
showed. That is recorded here rather than presented as a planned design.

The generated preamble for the A0 context, derived entirely from the runtime:

```text
Your bounded Clojure REPL projects exactly these operations under profile :agent/project-read:
  (data.json/read json-string) - Parse bounded integer-only JSON into Clojure data.
  (data.json/write value) - Encode bounded Clojure data as an integer-only JSON string.
  (project/read relative-path) - Read a UTF-8 file relative to the authorized project root.

That list is complete. You have no file listing, search, editing, shell, process,
network, or host API operation. Discover your operations before using them.
(apropos "") lists every operation you can call. (doc some/operation) shows one
operation's arguments, docstring, and effects. Only call operations that appear in
that list. If a task needs an operation that is not listed, say so plainly instead
of guessing at names.
```

The grounding constraint added by `:grounded`:

```text
Only state what your operations actually returned. You cannot enumerate a
directory, so never say what a project contains or that a file is absent; a read
failing does not mean a file does not exist. If a question requires enumeration,
say that you cannot enumerate and ask for explicit paths.
```

Arglists and docstrings come from the RuntimeCatalog. The preamble is bounded:
at most twelve operations are listed, each docstring is capped, and a larger
surface degrades to a count plus a pointer to `(apropos "")` rather than a dump.
A context projecting nothing produces no preamble, so a context with no
authority cannot gain a paragraph claiming it has some.

## 4. Method

Twelve live runs: four variants × three repetitions, each a fresh SQLite
session, all against the same endpoint and model as the A1 dogfood
(`Qwen3.6-27B-MTP-GGUF`, Lemonade OpenAI-compatible loopback), all using the
exact prompt that failed in A1:

> *"What files does this project contain, and what does each one do?"*

Every measurement is derived from the durable journal, not from return values.
`script/orientation-compare.clj` is the harness. The twelve runs produced four
distinct prompt digests, one per variant.

## 5. Results

| Variant | Finished | Attempts (mean) | Errors (mean) | Used discovery | Concluded limitation |
|---|---:|---:|---:|---:|---:|
| `:none` | 0/3 | 12.0 | 10.7 | **0/3** | 0/3 |
| `:minimal` | 1/3 | 11.0 | 5.7 | **3/3** | 0/3 |
| `:generated` | 2/3 | 10.3 | 6.7 | **3/3** | 0/3 |
| `:grounded` | **3/3** | **3.3** | **1.7** | **3/3** | **3/3** |

Per-run, from the journals:

```text
none       12 attempts  10 errors  failed
none       12 attempts  11 errors  failed
none       12 attempts  11 errors  failed
minimal    12 attempts   6 errors  failed
minimal    12 attempts   7 errors  failed
minimal     9 attempts   4 errors  finished
generated  10 attempts   6 errors  finished
generated  12 attempts   8 errors  failed
generated   9 attempts   6 errors  finished
grounded    5 attempts   2 errors  finished
grounded    4 attempts   3 errors  finished
grounded    1 attempt    0 errors  finished
```

### Discovery adoption is the clean result

`0/3` → `3/3`. Naming `apropos` and `doc` in one sentence made the model use
them on every subsequent run, in every oriented variant. Wasted actions roughly
halved. This part of the milestone's hypothesis is confirmed without
qualification.

### The part the milestone got wrong

`concluded-limitation` is **0/3 for `:generated`**, despite two of three runs
"finishing". Reading what those runs actually said is the finding:

> *"This project contains **2 files**..."*
>
> *"The project contains exactly two files..."*
>
> *"No other project files (like `bb.edn`, `deps.edn`, `project.clj`, etc.)
> were accessible."*

Nothing enumerated the directory, because nothing can. The model guessed
`README.md`, read it, and followed the reference to `src/example/core.clj`
inside it — the review corrected this from an earlier description of both
filenames as guesses. The discovery path was therefore reasonable; what was not
supported was the assertion of **completeness**. The fixture happens to contain
exactly those two files, so the answers are **accidentally correct**. With a
third file that the README does not mention, all three would have been
confidently wrong. The last quote is worse than wrong: it converts "my read
failed" into "the file is absent."

So on the headline metrics `:generated` looks like a success — fewer errors,
more completions, faster — while actually trading a *visible* failure for an
*invisible* one. A run that exhausts the action budget is at least legible as a
failure. A confident unsupported answer is not.

The failure mode did not disappear under orientation. It moved: from guessing
**operation names**, which errors loudly, to guessing **file names**, which
succeeds quietly and stays inside granted authority.

### What fixed it

`:grounded` constrains assertions rather than operations, and swept every
measure: 3/3 finished, 3/3 concluded the limitation, attempts down from 12.0 to
3.3, and errors from 10.7 to 1.7. It also had the fastest mean time, but that
measure is withdrawn: variants ran in blocks rather than interleaved and
`:grounded` ran as a later batch, so elapsed time is confounded with run order.
Attempts and errors are counts of model actions and are not.

The three answers, verbatim in substance:

- **one action** — `(apropos "")`, then: *"I cannot enumerate the files in this
  project... If you can provide me with specific file paths, I can read those
  files."*
- **four actions** — lists its three actual operations, then asks for paths.
- **five actions** — the best answer: separates *"I cannot enumerate the full
  list of files"* from *"however, I was able to read two files that the README
  references"*, and reports both honestly.

That last one is the behaviour worth wanting: correct about its limits, still
useful with what it has.

## 6. Authority

Unchanged, and tested. `orientation-does-not-change-authority-test` asserts that
all four variants produce an identical ContextSpec, identical grants, zero
projected classes, and zero supplied imports. Orientation changes only the
system message; `apropos` and `doc` were already in `catalog/base-allow` and
already worked.

No bb4t change was needed, as A1.1's scope predicted.

## 7. Tests

Suite at submission: **107 tests, 803 assertions, 0 failures.** The review
added three tests; see section 10.

New coverage: mode normalization and rejection of unknown modes; the generated
preamble deriving Vars, arglists, docstrings, and profile from the context; a
preamble that cannot invent authority (empty projections produce nothing; only
projected operations appear; a 40-operation surface is bounded to twelve plus a
pointer); composition ordering with the base prompt first; `:grounded` extending
rather than replacing `:generated`; each variant producing a distinct prompt
digest; identical authority across all variants; the composed prompt reaching
the provider's system message; and the orientation variant being recoverable
from the durable journal.

## 8. Nonclaims

- one model, one endpoint, one prompt, three repetitions per variant. This is a
  directional result on a small sample, not a benchmark, and the model is
  nondeterministic — `:minimal` and `:generated` overlap within noise on
  attempts and errors. The `:none` → oriented discovery difference (0/3 vs 9/9)
  and the `:grounded` conclusion difference (0/9 vs 3/3) are large enough to
  read through that noise; the ordering of `:minimal` versus `:generated` is
  not;
- one fixture project, whose README names the only other file. The A2 fixture
  requirement is therefore stronger than "filenames the model cannot guess":
  **no file may enumerate the others**, or the model can reconstruct the answer
  without enumeration and its completeness claim looks correct by accident;
- variants were not interleaved. All three `:none` runs ran, then `:minimal`,
  then `:generated`; `:grounded` ran later as a separate batch, recorded under
  `:grounded-followup` in `artifacts/a1-1-runs.edn`. Run order is therefore
  confounded with variant. This does not threaten the discovery or conclusion
  results, which are 0/3-versus-9/9 and 0/9-versus-3/3 differences in model
  behaviour, but it does invalidate the elapsed-time comparison;
- `concludes-limitation?` is a regular-expression judgement over the final
  message. Every classification in section 5 was additionally read by hand;
- no capability was added, so nothing here shows whether `project/list` is the
  right next capability, only that its absence is now correctly reported;
- the default remains `:none`. Nothing changed for existing callers;
- no native build or TUI evidence was produced for A1.1; the change is prompt
  composition on a path A1 already proved natively.

## 9. Recommendation

**Make `:grounded` the default**, then proceed to A2 with `project/list`.

*Done. `session/start!` defaults to `:grounded` as of the review closure; see
section 10.*

Two things follow from the results, and the second is the one worth carrying
forward.

First, the narrow one: orientation is cheap, derived, adds no authority, and
converts a total failure into a correct and useful answer. There is no reason
for a new session to start un-oriented. Changing the default is a one-line
change plus its evidence, and should be its own small step so the default flip
is attributable.

Second, the general one: **listing capability is not sufficient; claims need
constraining too.** Every future capability added to the catalog will be
enumerated automatically by `generated-preamble`, which is the property we
wanted. But nothing about enumerating operations prevents the model from
asserting things those operations cannot establish. That is a standing
epistemic constraint, not a per-capability one, and it belongs in the
orientation projection permanently rather than being rediscovered per
milestone.

A2 now has the clean signal the previous review asked for. The model
demonstrably **discovers its capability and correctly reports that directory
enumeration is missing**, rather than failing to discover anything. That is
evidence for `project/list`, and it also means A2 can measure whether adding it
changes the answer from *"I cannot enumerate, give me paths"* to a correct
enumeration — a sharper before/after than A1.1 had.

**An A2 entry condition, not a caution.** The review found that this problem is
three times larger than stated here. Absent authority is asserted as a hardcoded
constant in **three** places, not one:

| Location | Constant |
|---|---|
| `resources/bbagent/system.txt` | "You have no editing, shell, process, network, or host API authority in the REPL." |
| `orientation/generated-preamble` | "You have no file listing, search, editing, shell, process, network, or host API operation." |
| `orientation/grounding-constraint` | "You cannot enumerate a directory..." |

All three become false the moment `project/list` lands, and a stale claim here
is worse than no claim: `:grounded` works precisely because the model believes
what the prompt says about its own limits.

This qualifies the architectural property this milestone claims. `orientation`'s
docstring promises "not parallel prose that would have to be maintained as
capabilities change", and that promise holds for **what the model may call** —
the operation list is generated from the context description, and a new
capability appears in it automatically. It does **not** hold for **what the
model may not do**. The absence claims are exactly the parallel prose the
namespace was written to eliminate, one level up.

A2 must derive the absence claims from the effects the context actually grants,
in all three locations, before or alongside `project/list`. Treat this as an
entry condition. Deriving it now was deliberately not attempted: there is no
second capability to derive against, and changing the `:generated` text would
have broken comparability with the twelve recorded runs.

Stop here for review. Do not change the default or begin A2 without it.

## 10. Review

A fresh review of the A1.1 implementation and evidence was performed after
submission. **The verdict stands.** Every one of the twelve runs recomputes
exactly from `artifacts/a1-1-runs.edn` using the harness's own derivation, the
quoted model output is verbatim, and every classification was re-read by hand.
The authority test genuinely compares ContextSpec, grants, projected classes,
and imports across all four variants, and the preamble genuinely reads
`[:context/surface :projections]` — the same path `tui/viewmodel.clj` uses for
the capability pane.

Six findings. Three were fixed in code, three corrected this document.

| # | Finding | Disposition |
|---|---|---|
| 1 | the preamble contradicted itself on a truncated surface | fixed |
| 2 | three hardcoded absent-authority constants, not one | section 9, A2 entry condition |
| 3 | resume silently discarded the session's orientation | fixed |
| 4 | `--orientation` usage text omitted `grounded` | fixed |
| 5 | variants were not interleaved; timing confounded | sections 5 and 8 |
| 6 | the fixture's README enumerates the other file | sections 5 and 8 |

### 1. A truncated surface was described as complete

With more than twelve projected operations the preamble emitted *"exactly these
operations"*, *"... and 28 more"*, and *"That list is complete"* simultaneously.
The condition was latent — the A0 context projects three operations, so the
branch never fired in any measured run — but it would have fired in A2, and a
preamble whose entire purpose is to stop unsupported completeness claims must
not make one. A truncated surface now reports itself as partial and names the
true count.

### 3. Resume discarded the session's orientation

`resume!` defaulted orientation to `:none` and never consulted the recorded
coordinate, so a session started `:grounded` and resumed without repeating the
flag silently returned the model to the unoriented prompt — with a conversation
history that had been produced under grounding still in context. Attribution was
never wrong (the resumed coordinate honestly recorded `:none`), so this is a
continuity defect rather than an evidence defect.

A session now keeps the orientation it was started with; passing `:orientation`
explicitly overrides it for that run only. **Known limit:** the rule inherits
from the start coordinate, so a session resumed once with an override and again
without one returns to its start orientation. That is deterministic and
documented rather than surprising. Inheriting from the most recent run
coordinate would need a `latest-event` store operation across both backends,
which is not worth opening in a review-closure change.

### Evidence integrity

The fix in finding 1 changes only the truncation branch, so the prompts the
twelve runs actually received are byte-identical. Confirmed directly: all four
recorded prompt digests recompute from the current source.

```text
none       sha256:f69ebaed3336c...   match
minimal    sha256:b722113a1f0d6...   match
generated  sha256:e4a37c39078ee...   match
grounded   sha256:acacdd8c9cbf5...   match
```

Suite after review: **110 tests, 818 assertions, 0 failures**, adding one test
that a truncated surface is never claimed complete and three covering resume
inheritance, explicit override, and a session recorded before orientation
existed.
