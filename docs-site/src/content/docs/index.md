---
title: MCP Gateway Core
description: Java contracts and Spring WebFlux adapters for MCP tool governance.
template: splash
hero:
  title: MCP Gateway Core
  tagline: Java contracts and Spring WebFlux adapters for MCP tool governance.
  actions:
    - text: Get started
      link: guides/getting-started/
      icon: right-arrow
    - text: Contract reference
      link: reference/contract-reference/
      variant: secondary
---

`mcp-gateway-core` is a public-preview Java contract library for MCP
tool-governance runtimes. It provides shared vocabulary and deterministic
helpers for tool identity, authorization, policy decisions, audit events, abuse
protection, correlation IDs, URL scoping, and rate limiting.

The optional `mcp-gateway-spring-webflux` artifact adds a WebFlux governance
filter over those contracts. It is not a full gateway runtime, proxy data plane,
scanner integration, UI, or service mesh.

`mcp-gateway-core`'s lane is deliberately narrow: embeddable governance
contracts for Java MCP tool runtimes. It is not an MCP server SDK, Spring Boot
starter, OAuth provider, policy language, plugin system, proxy, or data plane.

The latest published preview release is `0.11.1`. It updates Spring WebFlux,
Spring Security Core, and Jackson Databind in the optional adapter. Both
libraries retain Java 17 support and their existing public APIs. Core remains
JDK-only.

The adapter checks tool availability before permissions when the host supplies
its active-tool registry. The core's combined rate-limit decision and retry-delay
result, `TokenBucketRateLimiter.attempt`, is available since `0.10.0`.
Version `0.11.0` introduced active-catalog selection, opt-in strict authorization,
metadata snapshots, adapter diagnostics, and an audit-observer bridge. See the
[release notes](maintainers/release-notes/) for upgrade guidance.

## Choose Your Path

| Need | Start Here |
| --- | --- |
| Wire the library into an MCP server | [Getting started](guides/getting-started/) |
| Trace a working Spring AI/WebFlux consumer | [Integration reference](reference/zap-integration/) |
| Understand every field and enum value | [Contract reference](reference/contract-reference/) |
| See package ownership boundaries | [Module map](reference/modules/) |
| Check compatibility promises and non-promises | [Compatibility](reference/compatibility/) |
| Review release history and migration notes | [Release notes](maintainers/release-notes/) |
| Understand where this project is going | [Roadmap](project/roadmap/) |

## Coordinates

These are the current `0.11.1` public-preview coordinates.

```groovy
implementation "io.github.dtkmn:mcp-gateway-core:0.11.1"
implementation "io.github.dtkmn:mcp-gateway-spring-webflux:0.11.1" // optional
```

## Boundary

Use this library when you are building an MCP gateway/security layer and need
shared Java contracts for tool-level governance.

Use a runtime gateway or proxy when you need traffic routing, Kubernetes Gateway
API integration, service discovery, backend federation, or data-plane
operations.
