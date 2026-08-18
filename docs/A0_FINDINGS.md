# A0 Findings

## 1. Verdict

**Pass.** The JVM application/runtime boundary, bounded persistent context,
deterministic fake-provider loop, durable journal, restart recovery, source-pinned
native build, and two-process live model read/resume path pass.

The central A0 question is answered affirmatively. The remaining limitations are
documented product debt and later runtime/build semantics, not reasons to enlarge A0.

## 2. Trusted Application Integration

Development loads trusted bbagent Clojure code and the BB1 host facade from sibling
repositories. Native builds use the bbagent-specific `:app/bbagent` Lein profile in
bb4t. The profile adds `../bbagent/src` and `../bbagent/resources`, selects
`bbagent.core`, and leaves ordinary bb4t builds unchanged. `script/build-native`
requires full exact source SHAs, checks out both repositories side-by-side, verifies
the resolved SHAs, embeds them as resources, and invokes bb4t's existing build.

bb4t required three small build-only edits:

- `project.clj`: one application profile;
- `script/uberjar`: explicit `BB4T_APPLICATION=bbagent` selection;
- native resource configuration: include bbagent prompt/provenance resources.

No runtime, kernel, catalog, ContextSpec, operation, event, or SCI projection code
changed.

Current coordinates:

```text
accepted BB1 tag/dev tip: 8cbee8e1f7106bb7b2d77050c7dc50abb805ce43
measured BB1 implementation: cedbcb9adcde61533cd023b71c14dc4b7c109cc3
bb4t A0 build hook: 896af34a7933a80f9fec16995d7a477354b49649
bbagent A0.1 implementation: c2c44f124f825e1886c5c6281b04f656bfff39e5
development embedded bb4t value: development
development embedded bbagent value: development
native embedded bb4t value: 896af34a7933a80f9fec16995d7a477354b49649
native embedded bbagent value: c2c44f124f825e1886c5c6281b04f656bfff39e5
```

The native wrapper pins the exact bb4t hook and bbagent implementation commits. It
does not falsely pin the accepted BB1 tip, which does not contain the application
hook.

## 3. Agent Loop

Actual flow:

```text
user message
  -> durable :user/message
  -> explicit bounded model request
  -> durable :model/request before HTTP/fake call
  -> normalized provider response and durable :model/response
  -> exactly one normalized AgentAction
  -> durable :agent/action
  -> :repl/request before context evaluation
  -> bb4t context/evaluate on the retained Context handle
  -> durable :repl/result and selected :bb4t/event values
  -> checkpoint
  -> another model step or :finish
```

The loop is single-agent and capped at 12 actions per user turn. The model context is
the system prompt plus at most 40 recent provider-neutral messages. Truncation removes
leading orphan tool results.

## 4. Authority

Every session materializes this complete ContextSpec:

```clojure
{:context-spec/version 1
 :profile :agent/project-read
 :requested-capabilities #{:data/json-read :data/json-write :project/read}
 :authorized-capabilities #{:data/json-read :data/json-write :project/read}
 :resource-bindings {:project :project/root}
 :limits {:project/read-max-bytes 1048576}}
```

The effective grants are the same three capabilities. Integration tests demonstrate
that `project/read` works in this context, fails in `:agent/minimal`, and that an
attempt to call `bb4t.runtime/create` from model SCI fails. Host runtime/context
handles and unrestricted semantic dispatch are never included in model messages or
SCI namespaces.

This is semantic restriction, not hard process or hostile-code isolation.

## 5. Provider

The production path is OpenAI-compatible chat completions over
`babashka.http-client`, with JSON through Cheshire. Endpoint, model, reasoning effort,
and API key are external configuration. Endpoint URLs containing user-info, query,
or fragment data are rejected. Provider descriptions omit the key.

The provider-specific `repl_eval` and `finish` calls normalize to:

```clojure
{:action/type :repl/eval :source "..."}
{:action/type :finish :message "..."}
```

Response identity, model, usage when supplied, finish reason, created value, and
integer latency are retained. Missing usage remains `nil`. Provider syntax remains in
the one provider encoder/decoder; it is not the bb4t action ABI.

Text becomes `:finish` only when the provider reports terminal `"stop"`; incomplete
reasons such as `"length"` and `"content_filter"` fail normalization. Tool responses
must report `"tool_calls"` and retain a non-empty provider call ID. Requests set
`parallel_tool_calls` false, and a round-trip HTTP-stub test verifies that an
assistant tool call ID is returned as the following tool message's `tool_call_id`.

Bearer credentials are accepted over HTTPS and plaintext loopback endpoints only.
Remote plaintext HTTP requires explicit `:allow-insecure-http true`, which is retained
in provider configuration and the session coordinate. Loopback literals are checked
with `InetAddress.isLoopbackAddress`, `localhost` must resolve only to loopback
addresses, and provider requests do not follow redirects.

Known leakage: neutral conversation messages retain action IDs because the provider
requires assistant/tool correlation. A0 has no second production provider proving
that this neutral representation is sufficient for every API.

## 6. Journal

Each session uses append-oriented, checksummed EDN-lines under:

```text
STATE_ROOT/sessions/SESSION_ID/events.edn
STATE_ROOT/sessions/SESSION_ID/blobs/SHA256
```

An append assigns stable event identity, sequence, and UTC time, writes one record,
and forces the file channel before returning. Every line contains a SHA-256 over a
canonical event representation. Event sequence continuity is checked on recovery.
Only an unterminated final line is discarded, and repair atomically replaces the
damaged file with its verified prefix. A malformed, blank, reordered,
checksum-invalid, or otherwise complete record fails recovery instead of being
silently erased.

UTF-8 strings over 65,536 bytes are content-addressed through a reserved EDN tagged
literal. Blobs are atomically published; an invalid pre-existing blob is replaced
before its reference is journaled. Recovery validates the blob's name, length, and
digest before rehydration. Canonical persistence is structured events, not a
formatted transcript.

Request/result pairs share `:request/id`; agent actions and REPL pairs additionally
share `:action/id`. The journal removes recognized credential fields recursively.
Provider authorization is never placed in an event. As with any local transcript,
users must not paste credentials into ordinary user/model/REPL content.

Session IDs reject `.` and `..`, continue to reject separators, and are normalized
under the sessions root with a parent-containment assertion before any filesystem
access.

bb4t events remain a bounded diagnostic source. Selected values are copied into
`:bb4t/event`; any snapshot drop count becomes an explicit
`:bb4t/events-dropped` journal event. The journal is not presented as a replacement
for bb4t's diagnostic buffer.

## 7. Resume

A graceful checkpoint stores provider-neutral conversation messages and ordered
replay entries:

```clojure
{:source "..." :expected-status :ok-or-error}
```

On restart, bbagent creates a new runtime and Context, then re-evaluates every entry.
Failed forms are replayed too because SCI may retain mutations made before failure.
Replay must reproduce each expected success/error status. Verified durable events
after the last checkpoint are folded into state, so a durable REPL result is not lost
merely because its following checkpoint was interrupted. A REPL request with no
durable result is treated as an ambiguous effect and fails recovery.

Survives restart:

- stable session ID and complete verified journal;
- conversation at the checkpoint plus foldable durable tail events;
- replayable successful and failed SCI forms;
- project root and recorded world provenance;
- explicit model/context/run coordinates.

Does not survive transparently:

- the original SCI heap, object identity, open resources, or host handles;
- non-replayable hidden state;
- old file contents if replayed `project/read` forms observe a changed project.

This is session/world resume with computational reconstruction, not heap persistence.
Each resume records a new run coordinate and whether Git revision/dirty provenance
changed. Replayed reads intentionally observe the current authorized world; old tool
messages remain historical evidence.

## 8. Coordinate Envelope

The journal currently records:

```clojure
{:session/id ...
 :run/id ...
 :runtime {:bb4t/commit ...
           :runtime/digest ...
           :catalog/digest ...}
 :agent {:bbagent/commit ...
         :profile :a0/single-agent}
 :model {:provider ...
         :endpoint ...
         :model ...
         :reasoning-effort ...
         :allow-insecure-http false-or-true-or-nil-for-non-HTTP-providers}
 :world {:project/root ...
         :project/revision nil-or-SHA
         :project/dirty? nil-or-boolean
         :project/repository nil-or-sanitized-URL}
 :context {:profile :agent/project-read
           :context-spec/digest ...
           :effective/digest ...
           :requested-capabilities ...
           :authorized-capabilities ...}
 :surface {:kind :persistent-sci :version 1}
 :prompt {:system/digest ...}
 :policy {:coordinate nil}}
```

Canonical hashing is order-independent for maps and sets. Tests confirm map ordering
does not change a digest and meaningful profile/grant changes do.

Git dirty provenance is tri-state: `true` means a successful status command found
changes, `false` means a successful status command was clean, and `nil` means status
was unavailable or failed.

## 9. Native Status

The pinned native application build passed. `script/build-native` fetched and
verified the exact bb4t and bbagent implementation commits, selected `bbagent.core`
as the image main class, embedded both source coordinates, and produced a runnable
executable.

```text
Leiningen              2.11.2
Java                   25.0.4+7-LTS
GraalVM distribution   Oracle GraalVM 25.2.4+7.1
native-image           25.0.4
native-image phase     33.7 s
binary bytes           50,989,312
binary SHA-256         0f1b1dc10276f26a5be75898f1496a018f32ea7d6b800c39b09f42ca6c064b44
```

The `25.0.4` value is the JDK/native-image language version; `25.2.4+7.1` is the
Oracle GraalVM distribution release containing it. They identify different toolchain
layers rather than conflicting versions. This A0 build pins source commits but does
not pin or validate Leiningen or GraalVM versions, and it differs from the BB1
evidence toolchain's recorded Leiningen 2.9.8. It is therefore a source-pinned product
build, not a fully toolchain-pinned reproducible build or BB1 measurement reproduction.

The final native executable passed `describe`, created and resumed a live session,
constructed fresh real BB1 runtime and `:agent/project-read` Context instances,
checkpointed, exited, listed the session, and inspected the durable journal. The
live session recorded:

```text
session                 f4e7b307-b9e9-4523-80f5-2d7575d0b6f6
run 1                   777c3727-136a-4eee-a063-f5a50d4d8c09
run 2                   2db55c70-3963-4f76-8728-40317ae1872b
runtime digest          sha256:cad47c1f697fe70b2aea92d6babedd8e4fc6ba06d05b14d0310aafdd331a8e6d
catalog digest          sha256:f132b513c4492e9cc9e22af088182d03d28b2059eab5c182dcbdf1db6e425f31
context-spec digest     sha256:56afcaaf18ea2ef16dbdf684bced9f45be2e93dc4a30e774ddab2f6f01297937
effective digest        sha256:974cd40e908736517f2b3c08b0bb6f289b59cfbd5cbf195fc3ac2acff24f5476
```

Two preliminary A0.1 images failed before journal creation or model traffic because
nested `Path.normalize` calls lost type information under native compilation. The
final implementation uses explicitly typed intermediate `Path` values and passed the
same startup path. This was a native-only defect discovered by the live gate.

The full deterministic suite still runs on the JVM; native build, live provider,
context, journal, stop/restart, replay, and resume behavior are proven, not complete
JVM/native test-suite parity.

## 10. Dogfood Result

The deterministic fake-provider scenario passed:

```text
user -> model action -> persistent def -> project/read -> result -> finish
exit -> new AgentSession -> replay -> next turn uses retained definition
```

Tests also pass for failed-form partial state, post-checkpoint durable result folding,
large payload blobs, provider errors/malformed actions, and authority negatives.

The native no-model smoke path also passed runtime/context construction, durable
checkpoint/end events, session listing, event inspection, exact coordinate embedding,
and credential omission.

A two-process live run used Lemonade's OpenAI-compatible loopback endpoint with
`Qwen3.6-27B-MTP-GGUF` and an explicit two-file fixture.

Run 1 asked the model to read `README.md` and `src/example/core.clj`, define
`retained-readme`, and explain both files. The trace contains model request/response,
normalized actions, correlated REPL requests/results, successful semantic
`project/read` events for both files, a successful persistent `def`, and `:finish`.
The model correctly described the 7-plus-5 counter and checkpoint phrase.

Run 2 resumed the same session in a new native process with a new run ID. All prior
successful and failed forms replayed with matching status. The model explicitly
evaluated `retained-readme`, received the reconstructed README value, and answered
that the checkpoint phrase was `"amber compass"`. The model did not initiate another
file read; replay itself intentionally re-read current-world files.

No API key, authorization header, or credential value appears in the 114-event
journal. Historical and resumed coordinates, request IDs, action IDs, usage, finish
reasons, runtime events, and checkpoints remain inspectable.

Operator usability is intentionally basic: line input, `/quit`, and EDN inspection.
The live model made several failed guesses at unavailable discovery/helper Vars before
using the documented `project/read` Var. Capability orientation needs a better A1
projection, but authority remained bounded and the loop recovered from each error.
The other awkward semantic is honest replay after project changes: old conversation
is historical while reconstructed definitions observe current files.

Deterministic JVM result:

```text
26 tests, 75 assertions, 0 failures, 0 errors
```

## 11. Known Nonclaims

- no hard isolation or resource bounds beyond BB1's semantic limits;
- no transparent SCI heap persistence;
- no general or automatic memory;
- no TUI;
- no project editing or process capability;
- no multi-agent, planner, reviewer, or subagent behavior;
- no model routing or second production provider;
- no hidden chain-of-thought persistence;
- no transactional multi-process journal writer;
- no complete JVM/native deterministic-suite parity result;
- no Cedar or other policy engine;
- no claim that Git identifies all project content when the tree is dirty.

BB2 must define whether RuntimeManifest `:compiled/universe` means selected source
and dependencies or physical native reachability. The A0 application changes native
main/AOT roots while BB1 still describes the upstream-default source universe. A
future BuildManifest should distinguish source universe, application/build profile,
native artifact, compiled capabilities, and a reachability coordinate. This semantic
clarification does not change A0's bounded Context authority.

Long-session storage is also explicit debt: checkpoints currently repeat complete
messages and replay forms, and recovery reads and hydrates the complete journal. A
later storage milestone should use incremental events, occasional content-addressed
checkpoints, a latest-checkpoint offset/index, and streaming tail recovery. A0 does
not introduce that framework before real usage data.

## 12. Recommendation for A1

Proceed to A1. The application seams are sufficiently clean: CLI code is thin, the
future TUI can remain a client of `AgentSession`, and the model-facing surface stays
owned by bb4t ContextSpec and semantic operations. Do not fold project discovery,
editing, process authority, memory, or multi-agent work into the TUI milestone merely
because the live trace exposed their future value.
