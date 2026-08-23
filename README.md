# bbagent

`bbagent` is a small personal agent application built on the `bb4t` runtime: a
separately maintained trusted host application, one persistent bounded SCI
context, model interaction through it, a durable event journal, and honest
restart recovery. A session reaches the project only through the semantic
capabilities its profile grants, and a resumed session reconstructs what it
computed rather than performing it again.

## Development

The sibling `../bb4t` checkout supplies the BB1 host facade during development.

```bash
clojure -M:test
clojure -M -m bbagent.core sessions
clojure -M -m bbagent.core tui --project .
clojure -M -m bbagent.core tui SESSION_ID
clojure -M -m bbagent.core run --project .
clojure -M -m bbagent.core resume SESSION_ID
clojure -M -m bbagent.core inspect SESSION_ID
```

The durable backend is explicit and defaults to `sqlite`, which stores all
sessions under `STATE_ROOT/bbagent.sqlite3`. `--store file` selects the
human-readable reference backend. An existing session must be opened with the
backend that stores it; there is no migration between the two.

`--profile` selects the capability surface and defaults to
`agent/project-develop`, which can change the project. `agent/project-survey`
is read-only and `agent/project-read` is the frozen A0 surface.
`agent/project-execute` adds `project/run`, which runs the project's own
commands in a disposable virtual machine; it needs `--tools DIR` (or
`BBAGENT_EXECUTOR_TOOLS`) naming the trusted tool bundle to mount, and refuses
to start if the host's machine manager is not a version whose isolation
behaviour has been measured. A resumed session keeps the profile it was created
with.

An OpenAI-compatible provider uses `OPENAI_API_KEY`, with endpoint and model
selected through CLI options or `OPENAI_BASE_URL` and `OPENAI_MODEL`. Credentials
are never included in journaled provider configuration.

HTTPS endpoints and plaintext loopback endpoints are allowed. Plaintext HTTP to a
non-loopback host requires the explicit `--allow-insecure-http true` override or
`BBAGENT_ALLOW_INSECURE_HTTP=true`. Provider requests do not follow redirects.

Project-owned commands run in a disposable virtual machine that sees the project
read-only and has no network. Under `agent/project-execute` the model reaches it
through one operation:

```clojure
(project/run {:argv ["bb" "script/a3a-source-check.clj"] :timeout-ms 120000})
```

That is the whole surface: argv, an optional relative `:cwd`, and an optional
`:timeout-ms` bounded by the context limit. Everything a run is bounded by is
host policy. A result names the exact project state the command ran against, or
says the project moved while it ran and names none — an unanchored run is
reported as `:project-changed` rather than as an ordinary success.
`script/a3a-source-check.clj` is the babashka check this is dogfooded against,
and `bb script/a3a-source-check.clj` runs it directly.

See `docs/CURRENT_SCOPE.md` for current milestone boundaries,
`docs/A3B_FINDINGS.md` for what execution does and does not claim,
`docs/A3A_FINDINGS.md` for the substrate underneath it, and
`docs/architecture/0001-trusted-application-inclusion.md` for native inclusion.
