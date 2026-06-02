# MCP Gateway Core

Java-only governance contracts and primitives for MCP tool gateways.

This repository is intentionally small. It is not a gateway runtime, router,
scanner integration, UI, or traffic-management data plane.

Current status: public preview. The package and coordinate are intended for
early integration proof, not a stable compatibility promise.

## Scope

Included:

- MCP tool invocation contracts
- MCP tool identity, surface, and capability registry contracts
- MCP tool authorization request, requirement, and evaluator contracts
- policy decision and evaluation contracts
- audit event, sink, and emitter contracts
- abuse-protection context and quota decision contracts
- correlation ID helpers
- URL-scope helpers
- token-bucket rate limiting primitives

Excluded:

- scanner integrations
- report, finding, queue, or evidence storage
- Spring Boot application wiring
- enterprise packaging
- A2A, LLM-provider, service-mesh, or Kubernetes gateway implementation
- product-specific tool names from downstream security packs

## Build

```bash
./gradlew verifyGatewayCorePublicPreviewPublication --no-daemon --stacktrace
```

This command runs the normal build, forbidden-coupling checks, closed-world JAR
checks, `jdeps`, unsigned Central Portal bundle validation, and signed dry-run
bundle validation with an ephemeral local GPG key.

## Coordinates

Planned public-preview coordinate:

```text
io.github.dtkmn:mcp-gateway-core:0.5.3
```

## Local Staging

```bash
./gradlew publishGatewayCorePublicationToGatewayCoreStagingRepository \
  -PgatewayCorePublicationRepositoryUrl="$(pwd)/build/staging-repository" \
  --no-daemon --stacktrace
```

## Release And Compatibility

- [Release policy](docs/RELEASE_POLICY.md)
- [Compatibility policy](docs/COMPATIBILITY.md)
- [Central validation upload](docs/CENTRAL_VALIDATION_UPLOAD.md)
