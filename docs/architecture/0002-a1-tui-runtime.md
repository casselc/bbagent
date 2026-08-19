# ADR 0002: A1 TUI Runtime

**Status:** Accepted for A1

## Context

A1 needs a native interactive terminal interface that is a projection and controller
over the existing `AgentSession`, store, and bb4t semantic state. The chosen runtime
must survive the bbagent native image alongside the S0b SQLite sidecar, and it must
not widen model-facing authority.

The selected bb4t runtime already compiles JLine `4.3.1`
(`jline-terminal-ffm`, `jline-terminal` excluding `jline-native`, `jline-reader`) and
already builds with the flags JLine's FFM provider requires: `--enable-preview`,
`-H:+SharedArenaSupport` (`script/compile`, commented "jline's FFM provider uses
Arena.ofShared"), `-H:+ForeignAPISupport` and `--enable-native-access=ALL-UNNAMED`
(`native-image.properties`), plus a populated `"foreign"` section in
`reachability-metadata.json`.

## Decision

Use **charm.clj `0.2.74`** (`de.timokramer/charm.clj`, Clojars, JAR SHA-256
`a142eec611db7493949dc29847329f6be2bde2fb7aa500a8b421cda00211c27e`) unchanged, as a
pinned dependency of the bbagent application build profile, driving JLine directly.

charm.clj is an Elm-architecture TUI library: `init`/`update`/`view` over a message
channel. That shape is the projection/controller separation A1 requires, so the
architectural constraint and the library's grain agree rather than fight.

The library is **not** forked, vendored, or adapted. Two build-configuration entries
are added; no charm or JLine source is modified.

## Compatibility Findings

These were measured, not read from a README. The published README states "JLine 3",
which is stale; the repository's actual `deps.edn` was the deciding evidence.

| Question | Finding |
|---|---|
| JLine version bb4t compiles | `4.3.1` |
| JLine version charm `0.2.74` expects | `4.3.1`, with the identical `org.jline/jline-native` exclusion |
| Does charm work unchanged? | Yes, on the JVM and natively |
| core.async skew | charm requests `1.9.865`; bb4t ships `1.8.741`. All 25 charm namespaces load and run against `1.8.741`. core.async use is confined to `charm/program.clj`. |
| Reflective call sites in charm | Exactly two, found by compiling with `*warn-on-reflection*` |
| Additional classes required | `org.jline.keymap.KeyMap`, `java.util.ArrayList` |
| Additional build-time init required | `org.jline.utils.InfoCmp$Capability` |

### The two build-configuration additions

1. `--initialize-at-build-time=org.jline.utils.InfoCmp$Capability`. charm's terminal
   namespace holds `InfoCmp$Capability` enum constants, and graal-build-time
   initializes Clojure namespaces at build time, so those constants reach the image
   heap while JLine classes default to run-time initialization. Without this the
   image **fails to build**, naming the exact type.

2. Reflection metadata for `org.jline.keymap.KeyMap` and `java.util.ArrayList`, in
   `resources/META-INF/native-image/bbagent/bbagent/reachability-metadata.json`.
   `charm/input/keymap.clj:134` calls the varargs `KeyMap.bind` with untyped
   arguments and `charm/render/core.clj:199` constructs an `ArrayList` reflectively.
   Without this the image **builds and then fails at run time** inside
   `clojure.lang.Reflector`.

Both failures were observed, diagnosed, and closed during the spike. Neither is
latent.

## Native Proof

A minimal program — start, draw, receive key input, resize, exit cleanly — was built
natively with the S0b toolchain (Oracle GraalVM `25.2.4+7.1`, native-image `25.0.4`)
using bb4t's JLine flags, and driven through a **real PTY** that writes keystrokes,
performs a `TIOCSWINSZ` resize with `SIGWINCH`, and waits on the exit status.

| Gate | JVM | Native |
|---|---|---|
| Draws initial frame | PASS | PASS |
| Receives key input | PASS | PASS |
| Resize `80x24` -> `100x30` observed | PASS | PASS |
| Clean exit line | PASS | PASS |
| Exit status `0`, terminal restored | PASS | PASS |

Native artifact: 35,588,352 bytes, SHA-256
`e97875d73fdde1e4928b6fc21b54564cda3ae3a85cb534d389568ad27b695dd3`, built in 24.0 s.
Native and JVM output were behaviourally identical, including charm's incremental
render diffing and its terminal-mode teardown on exit.

This is a bounded runtime proof on Linux x86_64 glibc. It is not a claim about the
full bbagent image, other platforms, or static linking; the full-image proof is A1
Phase 15 work.

## Authority

The TUI is trusted, AOT-compiled application code using ordinary Clojure interop. It
is **not** SCI-interpreted, so it needs no entry in `babashka.impl.classes`, which
exists only to expose classes to interpreted babashka scripts. The spike confirms
this: it compiled and ran natively without any such registration.

Adding TUI capability to the compiled image therefore does not add anything to a
model-facing Context. bbagent's bounded `:agent/project-read` Context projects zero
classes and zero supplied imports, and capability projection is driven by
`RuntimeCatalog`/`ContextSpec` rather than by image reachability. A1 extends the
existing authority negative corpus with `org.jline.*`, `charm.*`, and `bbagent.tui.*`
so this stays proven rather than assumed.

## Rejected Alternatives

- **Direct JLine only.** Viable, and the fallback if charm had failed. Rejected
  because it means hand-writing ANSI parsing, display width, render diffing, styling,
  and layout — roughly the 5,537 lines charm already provides and tests — for no
  authority or size benefit. Charm's cost turned out to be two configuration lines.
- **Forking or vendoring charm.** Considered because a JLine major-version mismatch
  was the expected risk. Measurement removed the premise: charm targets bb4t's exact
  JLine version. Vendoring would take on 5.5k lines of maintenance and lose upstream
  fixes to buy nothing. Reconsider only if upstream diverges from bb4t's JLine.
- **Pinning charm's requested core.async `1.9.865`.** Rejected to keep one core.async
  in the image; bb4t's `1.8.741` is proven sufficient.
- **`fulcro-tui`.** A Fulcro rendering target; adopting it would import a UI framework
  and its state model, directly against A1's constraint that the TUI introduce no
  second state model.
- **`clj-jline`.** A thin wrapper offering little over direct JLine, and no
  architecture.
- **Registering TUI classes in `babashka.impl.classes`.** Rejected as unnecessary and
  actively harmful: it would expose terminal classes to interpreted SCI, widening
  authority to buy nothing the compiled path needs.

## Consequences

- bb4t's `:app/bbagent` profile gains one pinned dependency and one
  `--initialize-at-build-time` entry. No bb4t runtime, kernel, catalog, ContextSpec,
  operation, or SCI projection change. The change stays independently reviewable.
- bbagent gains `resources/META-INF/native-image/bbagent/bbagent/reachability-metadata.json`.
- The TUI depends on charm's message/command model. `charm.program`'s event loop is
  the only core.async user; if it ever needs replacing, a blocking queue and plain
  threads would suffice, and the boundary is one namespace wide.
