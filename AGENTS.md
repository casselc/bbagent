# bbagent Development Guide

`bbagent` is trusted application code built on the public `bb4t` host facade.

Standing rules:

- keep model-facing authority inside one bounded `bb4t` SCI Context;
- do not expose host namespaces, handles, implementation functions, or secrets;
- keep the durable bbagent journal distinct from bounded bb4t diagnostic events;
- record explicit coordinates and honest unknown/development values;
- prefer the smallest implementation that answers the active milestone question.

For A3b (see `docs/CURRENT_SCOPE.md`):

- `project/run` takes argv, a relative cwd and a deadline, and nothing else;
  which project, which tools, which machine and what it may reach are host
  policy and must never acquire a model-facing spelling;
- bb4t knows there is an authorized execution environment, bbagent knows it is
  a virtual machine — keep `bb4t.execution` free of both, and keep the
  environment's description inert and free of host paths;
- a run whose project moved is not a run that verified anything; it gets its
  own status and carries no input coordinate;
- what a workload can see is what its result's coordinate accounts for — a new
  snapshot exclusion is a new thing to hide, not just a smaller manifest.

For A3a, still standing:

- project-owned code runs in a disposable worker, never against the
  authoritative checkout; the project is mounted read-only and the writable
  layer lives and dies inside the machine;
- a host subprocess gets a deadline, an output budget, and a reaped process
  tree — go through `bbagent.process`, never a bare `ProcessBuilder`;
- `bbagent.worker` is the only namespace that knows a machine manager exists;
  keep the manager's command line out of everything else;
- an execution result names the project state it ran against, or names none at
  all; never one the run only partly saw.

For A2, still standing:

- recovery reconstructs a session's computational state and never re-observes or
  re-actuates the project; a semantic operation invoked during replay returns its
  recorded receipt or recovery fails closed;
- an operation that touches the world declares an effect, and every effect is
  classified in `bb4t.catalog/effects` as an observation or an actuation;
- keep model-facing prose capability-independent: state closure, not absence, and
  let the derived orientation generate what the surface actually projects.

From A1, still standing:

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
