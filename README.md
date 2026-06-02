# MCP Gateway Core

Java governance contracts and primitives for MCP tool gateways.

This repository is intentionally small. It provides MCP-neutral Java value
types and helpers that downstream gateway runtimes can use for tool identity,
authorization, policy decisions, audit events, abuse protection, URL scoping,
correlation IDs, and rate limiting.

It is not a gateway runtime, router, scanner integration, UI, service mesh, or
traffic-management data plane.

Current status: public preview. The package and coordinate are intended for
early integration proof, not a stable compatibility promise.

## Why This Exists

MCP servers need a reusable governance layer around tool calls: identify the
tool, decide whether the caller may invoke it, apply quotas and rate limits,
record audit events, and keep product-specific integrations out of the shared
contract.

`mcp-gateway-core` is that contract layer. Downstream projects still own their
transport, authentication, storage, observability backend, and domain-specific
tool behavior.

## Relationship To Runtime Gateways

Runtime gateway projects such as
[agentgateway](https://agentgateway.dev/docs/standalone/main/about/introduction/)
provide a gateway control plane and proxy/data plane for routing, securing, and
observing MCP, LLM, A2A, and other agent traffic.

`mcp-gateway-core` is deliberately narrower. It is a Java library for reusable
MCP tool-governance contracts: tool descriptors, authorization requirements,
policy decisions, audit events, abuse-protection decisions, and small helper
primitives.

Use a runtime gateway when you need traffic routing, proxying, Kubernetes
Gateway API integration, backend federation, or data-plane operations. Use
`mcp-gateway-core` when you are building an MCP gateway/security pack and need
a shared Java contract for tool-level governance without adopting a full proxy
runtime.

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
- gateway execution context and principal/workspace model

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

Public-preview coordinate:

```text
io.github.dtkmn:mcp-gateway-core:0.5.4
```

Gradle:

```groovy
implementation "io.github.dtkmn:mcp-gateway-core:0.5.4"
```

Maven:

```xml
<dependency>
  <groupId>io.github.dtkmn</groupId>
  <artifactId>mcp-gateway-core</artifactId>
  <version>0.5.4</version>
</dependency>
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
