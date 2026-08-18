# bbagent

`bbagent` is a small personal agent application built on the `bb4t` runtime.
The A0 milestone proves a separately maintained trusted host application, one
persistent bounded SCI context, model interaction, a durable event journal, and
honest restart recovery.

## Development

The sibling `../bb4t` checkout supplies the BB1 host facade during development.

```bash
clojure -M:test
clojure -M -m bbagent.core sessions
clojure -M -m bbagent.core run --project .
clojure -M -m bbagent.core resume SESSION_ID
clojure -M -m bbagent.core inspect SESSION_ID
```

The durable backend is explicit. `--store file` remains the default; use
`--store sqlite` consistently with `run`, `resume`, `sessions`, and `inspect` to
store all sessions under `STATE_ROOT/bbagent.sqlite3`.

An OpenAI-compatible provider uses `OPENAI_API_KEY`, with endpoint and model
selected through CLI options or `OPENAI_BASE_URL` and `OPENAI_MODEL`. Credentials
are never included in journaled provider configuration.

HTTPS endpoints and plaintext loopback endpoints are allowed. Plaintext HTTP to a
non-loopback host requires the explicit `--allow-insecure-http true` override or
`BBAGENT_ALLOW_INSECURE_HTTP=true`. Provider requests do not follow redirects.

See `docs/CURRENT_SCOPE.md` for current milestone boundaries and
`docs/architecture/0001-trusted-application-inclusion.md` for native inclusion.
