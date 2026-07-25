# 0.7.1 Public API Baseline

These snapshots record the public/protected Java API surface from the published
`0.7.1` artifacts:

- `mcp-gateway-core`
- `mcp-gateway-spring-webflux`

The Maven Central JARs used to generate the snapshots had these SHA-256
digests:

- `mcp-gateway-core-0.7.1.jar`:
  `73d730bc4b83c9f2da9f9f5c4d560c7c6f8703fbd534d784efc6b6830df11c39`
- `mcp-gateway-spring-webflux-0.7.1.jar`:
  `1ae17372207475531042d486d8ced34876c8354c443b597a4a3ec5253d351e6e`

The root `verifyPublicApiSnapshots` Gradle task regenerates signatures from the
current compiled JARs and compares them with these files. Any addition or
removal must be accepted in `compatibility/accepted-api-deltas-0.8.0.json` and
linked from `docs/RELEASE_NOTES.md`.

The snapshot scope is intentionally limited to public/protected members under
`mcp.gateway.core.*` and `mcp.gateway.spring.webflux.*`.
