# 0.6.0 Public API Baseline

These snapshots record the public/protected Java API surface for the `0.6.0`
public-preview artifacts:

- `mcp-gateway-core`
- `mcp-gateway-spring-webflux`

The root `verifyPublicApiSnapshots` Gradle task regenerates signatures from the
current compiled JARs and compares them with these files. Any addition or
removal must be accepted in `compatibility/accepted-api-deltas-0.7.0.json` and
linked from `docs/RELEASE_NOTES.md`.

The snapshot scope is intentionally limited to public/protected members under
`mcp.gateway.core.*` and `mcp.gateway.spring.webflux.*`.
