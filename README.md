# MCP Gateway Core

Java-only governance contracts and primitives for MCP tool gateways.

This repository is intentionally small. It is not a gateway runtime, router,
scanner integration, UI, or traffic-management data plane.

## Scope

Included:

- MCP tool invocation contracts
- policy decision and evaluation contracts
- audit event contracts
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
./gradlew build --no-daemon --stacktrace
```

## Publish Dry Run

```bash
./gradlew publishGatewayCorePublicationToGatewayCoreStagingRepository \
  -PgatewayCorePublicationRepositoryUrl="$(pwd)/build/staging-repository" \
  --no-daemon --stacktrace
```

The first public release still needs a dedicated release policy, compatibility
matrix, signing flow, and downstream consumer proof.

