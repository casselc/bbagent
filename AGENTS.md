# bbagent Development Guide

`bbagent` is trusted application code built on the public `bb4t` host facade.

Standing rules:

- keep model-facing authority inside one bounded `bb4t` SCI Context;
- do not expose host namespaces, handles, implementation functions, or secrets;
- keep the durable bbagent journal distinct from bounded bb4t diagnostic events;
- record explicit coordinates and honest unknown/development values;
- prefer the smallest implementation that answers the active milestone question.

For A1 (see `docs/CURRENT_SCOPE.md`):

- the TUI is a projection and controller, never a second state model or authority
  path; domain truth stays in `AgentSession`, the store, and the bb4t Context;
- reach semantic state only through `bbagent.session`, `bbagent.agent`,
  `bbagent.storage`, `bbagent.store`, and the public bb4t facade;
- do not depend on `bbagent.sqlite-store` internals, JDBC, or SQL schema details;
- new sessions default to SQLite; never infer, convert, or migrate the backend of an
  existing session (`docs/S0_CLOSURE.md`);
- keep TUI classes and namespaces out of every model-facing Context and extend the
  authority negative corpus whenever the image gains capability.

Run deterministic tests with `clojure -M:test`.
