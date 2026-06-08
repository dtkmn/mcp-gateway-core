# Module Map

This repository publishes two public-preview Java artifacts:

| Artifact | Package root | Purpose |
| --- | --- | --- |
| `mcp-gateway-core` | `mcp.gateway.core` | JDK-only MCP governance contracts and primitives. |
| `mcp-gateway-spring-webflux` | `mcp.gateway.spring.webflux` | Optional Spring WebFlux filters and adapters over the core contracts. |

The design rule is simple: core owns MCP-neutral vocabulary and small,
deterministic primitives. Adapter artifacts may own framework glue. Runtime
projects still own authentication providers, storage, policy sources,
observability backends, and domain-specific tools.

## Package Map

| Package | Owns | Does Not Own |
| --- | --- | --- |
| `mcp.gateway.core.invocation` | Normalized MCP tool invocation value types and invocation kind. | JSON-RPC parsing, HTTP transport, MCP SDK wiring. |
| `mcp.gateway.core.tool` | Tool descriptors, surfaces, capabilities, and in-memory registry lookup. | Product-specific tool names, tool execution, dynamic discovery. |
| `mcp.gateway.core.context` | Principal, workspace, and execution context records shared across governance decisions. | Auth providers, tenant stores, workspace databases. |
| `mcp.gateway.core.authz` | Authorization request, requirement, access registry, authorizer, pipeline, and decision mechanics. | OAuth/JWT/API-key validation, product-specific scope catalog ownership, runtime mode flag ownership. |
| `mcp.gateway.core.policy` | Policy evaluation context, decision, outcome, and deny exception vocabulary. | Policy bundle parsing, extension hooks, persistence, audit shipping. |
| `mcp.gateway.core.policybundle` | First-match policy rule evaluation, rule-list invariants, selector matching, trace details, and default-decision fallback. | JSON/schema parsing, policy bundle storage, extension hooks, product-specific bundle examples. |
| `mcp.gateway.core.audit` | Audit event records plus sink/emitter contracts. | Logging frameworks, metrics systems, storage, redaction policy for app-specific fields. |
| `mcp.gateway.core.protection` | Abuse-protection context, quota limits, and allow/reject decisions. | Queue inspection, workspace counters, backpressure wiring, rate-limit properties. |
| `mcp.gateway.core.rate` | JDK-only token-bucket rate limiter with bounded key tracking. | Distributed rate limiting, cache coordination, operator configuration. |
| `mcp.gateway.core.logging` | Correlation ID constants and sanitization/resolution helpers. | MDC wiring, web filters, request attributes. |
| `mcp.gateway.core.url` | URL scope normalization and matching helpers. | Target allowlist policy, scan evidence selection, crawler behavior. |
| `mcp.gateway.spring.webflux` | WebFlux filters, JSON-RPC request parsing, request body replay, safe denial/rejection responses, and resolver interfaces for mapping Spring requests into core context. | Spring Boot auto-configuration, Spring AI SDK integration, auth-provider setup, persistence, product-specific scope catalogs. |

## Current Consumer Pattern

A downstream MCP gateway or security pack should:

1. Parse the incoming MCP request in its own transport layer.
2. Convert the request into core invocation/context value types.
3. Evaluate authorization, policy, audit, abuse-protection, and rate-limit
   decisions through core contracts or primitives.
4. Execute domain-specific tool behavior only after the runtime-specific
   gateway layer accepts the call.
5. Keep domain objects, scanner adapters, Spring configuration, and persistence
   out of this library.

## What Belongs Here

Good candidates:

- value types that describe MCP tool governance without naming a specific
  runtime;
- deterministic helpers with no framework dependencies;
- decision objects that downstream runtimes can emit, adapt, or persist;
- small primitives whose semantics should be identical across consumers.

Bad candidates:

- controllers or Spring Boot auto-configuration;
- scanner, report, finding, evidence, or queue models;
- SaaS tenancy implementation;
- product-specific policy files or tool names;
- proxy/data-plane routing.

## Dependency Boundary

The `mcp-gateway-core` artifact must remain JDK-only. The release gate enforces
this with `jdeps` and closed-world JAR checks. Framework dependencies belong in
separate adapter artifacts such as `mcp-gateway-spring-webflux`, which must keep
their own closed-world and forbidden-coupling checks.
