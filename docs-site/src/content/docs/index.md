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

The optional `mcp-gateway-spring-webflux` artifact adds WebFlux filters over
those contracts. It is not a full gateway runtime, proxy data plane, scanner
integration, UI, or service mesh.

## Choose Your Path

| Need | Start Here |
| --- | --- |
| Wire the library into an MCP server | [Getting started](guides/getting-started/) |
| Understand every field and enum value | [Contract reference](reference/contract-reference/) |
| See package ownership boundaries | [Module map](reference/modules/) |
| Check compatibility promises and non-promises | [Compatibility](reference/compatibility/) |
| Understand where this project is going | [Roadmap](project/roadmap/) |

## Coordinates

```groovy
implementation "io.github.dtkmn:mcp-gateway-core:0.5.10"
implementation "io.github.dtkmn:mcp-gateway-spring-webflux:0.5.10" // optional
```

## Boundary

Use this library when you are building an MCP gateway/security layer and need
shared Java contracts for tool-level governance.

Use a runtime gateway or proxy when you need traffic routing, Kubernetes Gateway
API integration, service discovery, backend federation, or data-plane
operations.
