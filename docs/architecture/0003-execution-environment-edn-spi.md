# ADR 0003: ExecutionEnvironment EDN SPI (bbagent side)

**Status:** Accepted

**Date:** 2026-08-27

## Context

bb4t's `ExecutionEnvironment` is a Clojure protocol: two functions
(`-describe`, `-execute`) resolved in one process. That is the right shape
for what it is — the seam between bb4t's semantic layer and the trusted
host that supplies the somewhere — and it does not cross a repository
boundary. A protocol is an agreement between namespaces that share a
classloader; bbagent and whatever harness might one day consume its
execution evidence do not.

The question this ADR answers is narrower than "an interchange format".
It is: can bbagent state what its SmolVM execution environment is, whether
one can be had, what a run produced, and what a replay restored — as data
that a second, independent implementation could render **byte-identically**
and hash to the same digests — without modifying bb4t, Samizdat, or Jolt,
and without claiming one new thing about the substrate?

The boundary conditions, all of them binding:

- bbagent implements its side only. No change to the bb4t runtime
  protocol, to `project/run` semantics, or to any Samizdat/Jolt repository.
- No new SmolVM behavior. The envelope contents are projections of results
  `bbagent.executor`, `bbagent.worker`, and bb4t's `execution-result`
  already produce, ported into conformance fixtures — every canned input
  below is lifted from an existing test or from the executor's own
  authored refusal points.
- Existing project/run semantics survive unchanged: an exit only when the
  workload actually exited; a run whose project moved is `:project-changed`
  and carries no input coordinate; a refusal rather than a euphemism.

## Decision

Adopt **EDN envelopes, version 1**, kept by one namespace:
`bbagent.spi` (the envelope keeper — shapes, canonical rendering,
coordinates) and `bbagent.spi-smolvm` (the adapter over the existing
executor/worker results). `bbagent.spi` depends on nothing outside
Clojure itself, so another repository can lift it verbatim to consume or
produce conforming envelopes.

### 1. Canonical rendering (normative)

`bbagent.spi/render` produces the canonical EDN text of an inert value.
Two conformant implementations over equal data produce equal bytes.

- Scalars: `nil`, `true`/`false`, integers in decimal, strings in EDN
  readable form (Clojure `pr-str` under `*print-readably*` and no length
  or level limits), keywords and symbols in `:ns/name` form.
- Collections: elements and map entries joined by `", "`; a map entry is
  `key` + `" "` + `value`; vectors `[...]` and lists `(...)` keep their
  order; **maps sort entries ascending by rendered key text**; **sets sort
  by rendered element text**. In-memory ordering never reaches the bytes.
- The domain is inert data only: nil, booleans, integers, strings,
  characters, keywords, symbols, maps, vectors, sets, lists. Floats,
  records, metadata, tagged literals, keywords whose names EDN cannot
  spell, and arbitrary objects are **refused**, not printed. A value that
  cannot round-trip cannot be compared byte-for-byte, so it is not in the
  domain.

### 2. Coordinates (normative)

```
coordinate(kind, payload) =
  "sha256:" + lowercase-hex(SHA-256(UTF-8(render([:spi.coordinate/v1 kind payload]))))
```

`kind` is a qualified keyword. The `[:spi.coordinate/v1 ...]` tag domain-
separates SPI coordinates from each other (a description and an availability
never share one) and from bb4t's `[:bb4t.coordinate/v1 ...]` and bbagent's
`[:bbagent.coordinate/v1 ...]` over identical data. They name different
things; they must never collide.

`environment-coordinate(description) = coordinate(:spi.environment/description, description)`.
A describe envelope's coordinate is recomputed by validation from the
description beside it, so a misattributed envelope is a detectable lie.

### 3. Envelope kinds (normative)

Every envelope carries the frame `:spi/version 1` and `:spi/kind`, has an
exact key set (unknown keys are refused), and must be wholly inert.

**`:spi.environment/describe` — what an environment is**

```
{:spi/version 1 :spi/kind :spi.environment/describe
 :environment/description {…inert, non-empty map…}
 :environment/coordinate  "sha256:<64hex>"}
```

The description is opaque to the SPI. bbagent's own descriptions (the
inert, host-path-free maps `bbagent.executor/create` already publishes)
carry at least `:executor/type`; the adapter refuses one that does not,
because a run cannot be attributed to an environment that cannot say what
implements it.

**`:spi.environment/availability` — whether one can be had**

```
{:spi/version 1 :spi/kind :spi.environment/availability
 :environment/available? true
 :environment/coordinate "sha256:<64hex>"}
```
or
```
{:spi/version 1 :spi/kind :spi.environment/availability
 :environment/available? false
 :environment/refusal {:refusal/category <below> :refusal/reason "<authored string>"}}
```

Exactly one of `:environment/coordinate` / `:environment/refusal`:
an available environment carries no refusal, a refused one no coordinate.

Refusal categories — the executor's own refusal points, none invented:

| Category | Refusal point (`bbagent.executor`) |
|---|---|
| `:spi.refusal/manager-unavailable` | no machine manager / describe failed |
| `:spi.refusal/manager-unmeasured` | version not in the approved set, no host override |
| `:spi.refusal/guest-image-unusable` | archive missing, unreadable, or not a regular file |
| `:spi.refusal/guest-image-digest-mismatch` | archive digest ≠ pinned digest |
| `:spi.refusal/project-identity` | root-owned project; no unprivileged identity to derive |
| `:spi.refusal/unknown` | a refusal the catalogue does not name |

Reasons are authored strings. The executor's unreadable-image failure
carries an exception message that can name a host path; the envelope
carries the paraphrase instead, because a refusal crossing a repository
boundary has the same inertness obligation as a description.

**`:spi.execution/run` — what a run produced**

```
{:spi/version 1 :spi/kind :spi.execution/run
 :run/invocation-index <positive integer>
 :run/attribution {:environment/coordinate "sha256:<64hex>"
                   :environment/type <keyword>}
 :run/input  {:input/coordinate "sha256:<64hex>"}   ; or, below
 :run/input  {:input/stability :input/project-changed}
 :output/status :completed|:timeout|:worker-failure|:project-changed
 :output/exit <integer>                              ; iff status :completed
 :output/process {:process/status … :process/exit …} ; iff status :project-changed
 :output/stdout {:stream/text s :stream/bytes n :stream/truncated? b}
 :output/stderr {:stream/text s :stream/bytes n :stream/truncated? b}
 :output/duration-ms <non-negative integer>
 :output/error <string>                              ; iff status :worker-failure
 :run/disposition :terminated}
```

These are bb4t's `execution-result` rules, restated: the worker status
vocabulary is `#{:completed :timeout :worker-failure}` and an unknown
status fails closed; `:project-changed` is derived, never reported by the
substrate; **an exit is present if and only if the status is
`:completed`** (a deadline is not a program that chose a number); **a
changed project carries no input coordinate** — validation refuses one —
and **must** demote its process outcome into `:output/process`, so no
reader can match an unanchored run against `{:status :completed :exit 0}`;
streams carry their true byte counts (`:stream/bytes` is what the workload
wrote, not what was kept) and truncation flags; `:run/disposition` names
what happened to the machine, and `:terminated` is the one disposition the
substrate has ever had.

`:run/invocation-index` is which execution of this environment produced
the envelope: `bbagent.executor` increments its counter *before* each
`-execute`, so the counter read after a run returns is that run's index.

**`:spi.execution/replay` — what a replay restored**

```
{:spi/version 1 :spi/kind :spi.execution/replay
 :replay/invocation-index <positive integer>   ; the recorded run's index
 :replay/invocation-count  <non-negative integer>} ; live counter, after
```

A faithful replay performs nothing, so the counter it reports is the
counter that was there before it ran. The proof brackets the replay with
the count (as `bbagent.execution-test` already does through the session
envelope); the envelope carries both numbers so the bracket is auditable
after the fact. Validation refuses `index > count` — the environment that
witnessed a run cannot hold a counter lower than that run's index.

### 4. Conformance fixtures and golden SHAs

`test/fixtures/spi-v1/` holds one byte-identical fixture per envelope,
each with a golden `sha256sum`-format `.sha256` beside it. The fixtures
are generated by `bbagent.spi/render` from canned inputs ported from the
existing suites (the stub results of `bbagent.execution-test`, the
truncation bounds of `bbagent.worker-test`, and the refusal data shapes of
`bbagent.executor`'s own `fail!` sites). Any conformant implementation —
in any repository — must render the same inputs to the same bytes, and
`sha256sum -c` in that directory must pass. The conformance suite
(`bbagent.spi-test`) checks all three: bytes, digest, and EDN round-trip
(`render(read-envelope(fixture))` is the fixture, byte for byte).

Regeneration, if a fixture is ever deliberately changed, is the same
procedure that produced them: render the canned inputs with the keeper,
write the bytes, write the digest. There is no generator script to drift;
the test suite holds the same inputs and fails if the committed bytes
disagree with them.

### 5. What this does not claim

- **No new SmolVM behavior.** The adapter is a projection of results that
  already exist. Nothing here runs, bounds, or measures anything new.
- **No change to the bb4t protocol or `project/run` semantics.** The
  SmolVM environment, the Context resource, the capability, and the
  kernel's result semantics are untouched; sessions, journals, and replay
  are untouched. The envelopes are produced beside the existing paths,
  not instead of them.
- **No live wiring.** No journal, session, or runtime path emits envelopes
  yet. This ADR establishes the shapes, the adapter, and the conformance;
  adopting them anywhere is a separate decision with its own review.
- **No claim about another repository's implementation.** The SPI is
  repository-neutral by construction; whether Samizdat or Jolt consumes it
  is theirs to decide, and nothing here depends on them.

## Rejected Alternatives

- **Reuse bb4t's `ExecutionEnvironment` protocol as the SPI.** A protocol
  is an in-process contract; crossing repositories with it means sharing
  code, versions, and a classloader. Rejected: the boundary this SPI
  exists for is exactly the one a protocol cannot cross.
- **Coordinate via `bb4t.canonical/coordinate` (or `bbagent.coordinates`).**
  Both produce deterministic digests, but they version and evolve with
  their own repositories; an SPI coordinate that silently redefines itself
  because a *different* repository shipped a change is broken by design.
  The keeper carries its own, domain-separated algorithm (4 lines of
  MessageDigest) instead of a cross-repository dependency for the sake of
  not duplicating four lines.
- **JSON.** Keyword namespaces, sets, and the `(sorted) map` discipline
  survive EDN round-trips natively; in JSON they become conventions.
  bbagent and bb4t's lingua franca for inert semantic data is already EDN
  (coordinates, evidence artifacts, manifests).
- **Binary formats (messagepack/protobuf).** Byte-identity is achievable,
  but at the cost of a schema toolchain per language and unreadable
  fixtures. The evidence discipline here runs on diffable text.
- **Adding envelope methods to the bb4t protocol.** Would modify bb4t —
  out of bounds for this work — and would couple every consumer to bb4t's
  release cycle for a data concern.

## Consequences

- bbagent gains two namespaces: `bbagent.spi` (keeper, ~self-contained,
  liftable) and `bbagent.spi-smolvm` (adapter over executor/worker
  results). Existing namespaces are unchanged; `bbagent.execution-test`
  and `bbagent.worker-test` keep running unmodified.
- The conformance suite `bbagent.spi-test` is registered in the test
  runner (the invariant `script/a3a-source-check.clj` checks).
- The envelope version is 1 and stays 1 until a rule changes rendered
  bytes; then it is 2, and coordinates under 1 remain what they were.
- If a consumer repository adopts the SPI, the fixtures and goldens are
  the contract: same inputs, same bytes, same digests, or the
  implementations are not conformant.
