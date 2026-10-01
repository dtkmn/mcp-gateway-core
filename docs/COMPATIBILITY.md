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

The published `0.10.0` release adds the public core
`TokenBucketRateLimiter.attempt(String, Policy)` method and `Attempt` result
while retaining `tryConsume` and `retryAfterSeconds`. The new method returns a
consumption decision and its retry delay from the same attempt. The core stays
framework-neutral with no runtime dependencies.

The release's `toolRegistry(McpToolRegistry)` builder option is an opt-in,
additive change to `mcp-gateway-spring-webflux`. It accepts the existing core
registry directly; no additional catalog or observer contract is introduced.
Explicit null is rejected. Existing public constructors and registry-omitted
builder configurations preserve their behavior. This adapter option does not
change core tool-registry or authorization contracts, or the framework-neutral
authorization engine; the rate-limiter API addition above is separate.

Supplying a registry explicitly changes tool-call handling: existence is checked
before permissions, unknown and disabled tools receive the same generic MCP
error, enforced unmapped-tool failures return a generic internal error,
and tool-call request identifiers and notifications receive the documented
handling. Filtering and request-body validation remain active with a registry
even when authorization and protection are disabled. See the
[active-tool registry contract](https://danieltse.org/mcp-gateway-core/reference/contract-reference/#active-tool-registry)
for the wire responses and migration details.

This option does not discover tools automatically. Each hosting runtime must
supply an immutable registry of exactly its registered, enabled tools, validate
complete permission mappings, and keep discovery and dispatch consistent. An
existing `McpToolAccessRegistry.toolRegistry()` can be reused when that access
registry was built from validated active-only rules. No third tool list needs
to be maintained. A library upgrade without that integration retains legacy
behavior.
The generic adapter does not gain a Spring AI dependency or perform ZAP Server
wiring.

### Unreleased 0.11.0 Changes

The unpublished `0.11.0` candidate adds the strict authorizer overload, opt-in
metadata snapshot utility, and `McpToolAccessRegistry.activeToolRegistry(Collection<String>)`
in core. The catalog helper validates its input strictly without changing the
existing registry lookup normalization. Existing decision constructors retain
their checks and signatures; the decision-contract documentation describes their
limits and the supported evaluation paths.

The adapter adds an optional typed rejection observer and
`McpGatewayAuditObservers`, which connects the four observer setters to
an application-supplied audit sink. Setters retain their replacement semantics;
combining metrics and audit callbacks is explicit. Existing constructors and
observer-omitted configurations remain supported. No audit persistence is
installed automatically.

Resolver validation deliberately rejects a null or mismatched tool execution
context before governance. Equivalent context copies remain valid. When both
authorization and protection are disabled and no active-tool registry is
configured, filtering remains inactive. Idle rate-bucket retirement now uses
each bucket's stored refill period and requires full replenishment before
removal, preserving debt across unrelated policy lookups. New-key admission
checks at most 64 retirement candidates and rotates retained candidates across
attempts. A full map may temporarily reject a new key with the one-second retry
delay before a later attempt reaches an eligible bucket. A smaller incoming
tracked-key limit may also require multiple retirement passes. Public signatures
remain available.

These changes are not in published `0.10.0`. The final release artifacts still
require their release checks, including the Java 17 consumer check. The public
preview compatibility limits above continue to apply.

For `0.9.0`, the WebFlux adapter adds the fluent
`McpGatewayWebFluxGovernanceFilter` builder. Existing public constructors,
defaults, and governance behavior remain unchanged. The framework-neutral core
API is unchanged.

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
filtering is active, exact pass-through when filtering is inactive, and the
batch distinction: JSON-RPC batch arrays are unsupported by the governance
adapter while authorization, protection, or a configured tool registry
keeps filtering active. They pass downstream unchanged only when authorization
and protection are inactive and no tool registry is configured.

Stable compatibility can be declared only after downstream consumers prove the
API shape in real integration workflows.
