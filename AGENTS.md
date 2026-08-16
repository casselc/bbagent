# bbagent Development Guide

`bbagent` is trusted application code built on the public `bb4t` host facade.

For A0:

- keep model-facing authority inside one bounded `bb4t` SCI Context;
- do not expose host namespaces, handles, implementation functions, or secrets;
- keep the durable bbagent journal distinct from bounded bb4t diagnostic events;
- record explicit coordinates and honest unknown/development values;
- prefer the smallest single-agent implementation and do not begin A1 work.

Run deterministic tests with `clojure -M:test`.
