# ADR 0001: A0 Trusted Application Inclusion

**Status:** Accepted for A0

## Decision

Development loads bbagent and the BB1 host facade from sibling repositories. A
native application build activates one bbagent-specific Lein profile in bb4t. That
profile adds sibling `../bbagent/src` and `../bbagent/resources`, AOT-compiles
`bbagent.core`, and selects it as the image main class.

The bbagent build wrapper is responsible for checking out exact bb4t and bbagent
commits side-by-side and replacing both development provenance resources before it
invokes bb4t's existing uberjar/native-image pipeline. The ordinary bb4t build does
not activate this profile.

This changes compiled trusted host reachability only. It does not add any namespace,
class, Var, or operation to a model-facing SCI Context.

## Rejected Alternatives

- Runtime loading bbagent source through ordinary Babashka SCI would make the
  application interpreted model-adjacent code rather than trusted host code.
- Copying bbagent into bb4t would erase repository ownership and source coordinates.
- A new generalized build-profile framework belongs to BB2, not A0.
- An independent native-image pipeline would duplicate bb4t's substantial build and
  reachability configuration before there is evidence that duplication is useful.
