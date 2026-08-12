# MCP Gateway Core Compatibility Policy

This policy defines what the public-preview API currently promises and what it
does not promise.

## Public Surface

The framework-neutral public surface is the Java package:

```text
mcp.gateway.core
```

The core Maven coordinate is:

```text
io.github.dtkmn:mcp-gateway-core
```

The optional Spring WebFlux adapter surface is:

```text
mcp.gateway.spring.webflux
```

with Maven coordinate:

```text
io.github.dtkmn:mcp-gateway-spring-webflux
```

## In Scope

Public-preview consumers may depend on:

- MCP tool invocation value types;
- MCP tool identity, surface, and capability registry value types;
- authorization request, requirement, evaluator, and decision value types;
- policy decision value types;
- audit event, sink, and emitter value/contract types;
- abuse-protection context and quota limit value types;
- framework-neutral gateway governance orchestration types;
- gateway execution context, principal, workspace, and tool execution context value types;
- correlation ID helpers;
- URL scope helpers;
- token-bucket rate limiting primitives;
- optional Spring WebFlux governance filter and resolver interfaces for applying
  core authorization and abuse-protection decisions to MCP HTTP requests.

## Out Of Scope

This artifact does not promise:

- dynamic plugin loading;
- runtime routing;
- scanner integration;
- report, finding, evidence, or queue storage;
- Spring Boot application wiring;
- Spring Boot auto-configuration;
- Spring AI SDK integration;
- product-specific tool naming;
- stable binary compatibility yet.

## Compatibility Rule

During public preview, changes should stay source-compatible when reasonable,
but correctness and clean boundaries win over compatibility. Any breaking change
must be deliberate, reviewed, and described in release notes.

The development branch for the planned `0.9.0` release adds a fluent
`McpGatewayWebFluxGovernanceFilter` builder. This is a source- and
binary-compatible addition: all existing public constructors remain available,
and the builder delegates to the same full filter construction path. It does not
add Spring Boot auto-configuration or change ownership of authentication,
workspace resolution, policy, protection, or observations. The builder is not
available in the published `0.8.0` artifact.

For `0.8.0`, the WebFlux adapter deliberately moves its public JSON boundary
from Jackson 2 `ObjectMapper` to Jackson 3 `JsonMapper`. This is an intentional
source and binary break. Existing adapter consumers must update their mapper
imports and constructor wiring. The framework-neutral core API is unchanged.

For `0.7.0`, the API changes were compatible additions in
`mcp-gateway-spring-webflux`: the `McpInvalidRequestObserver` interface, its
`rejected(String reason, String requestId, String correlationId)` method and
`noop()` factory, and a new `McpGatewayWebFluxGovernanceFilter` constructor
overload that accepts the observer. Existing public constructors remain
available.

The WebFlux adapter documents fail-closed invalid request-shape handling when
governance is active, exact pass-through when governance is inactive, and the
batch distinction: JSON-RPC batch arrays are unsupported by the governance
adapter while governance is active, but pass downstream unchanged when both
authorization and protection governance are inactive.

Stable compatibility can be declared only after downstream consumers prove the
API shape in real integration workflows.
