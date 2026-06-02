# MCP Gateway Core Compatibility Policy

This policy defines what the public-preview API currently promises and what it
does not promise.

## Public Surface

The public surface is the Java package:

```text
mcp.gateway.core
```

The Maven coordinate is:

```text
io.github.dtkmn:mcp-gateway-core
```

## In Scope

Public-preview consumers may depend on:

- MCP tool invocation value types;
- MCP tool identity, surface, and capability registry value types;
- authorization request, requirement, evaluator, and decision value types;
- policy decision value types;
- audit event, sink, and emitter value/contract types;
- abuse-protection context and quota limit value types;
- gateway execution context, principal, workspace, and tool execution context value types;
- correlation ID helpers;
- URL scope helpers;
- token-bucket rate limiting primitives.

## Out Of Scope

This artifact does not promise:

- dynamic plugin loading;
- runtime routing;
- scanner integration;
- report, finding, evidence, or queue storage;
- Spring Boot application wiring;
- product-specific tool naming;
- stable binary compatibility yet.

## Compatibility Rule

During public preview, changes should stay source-compatible when reasonable,
but correctness and clean boundaries win over compatibility. Any breaking change
must be deliberate, reviewed, and described in release notes.

Stable compatibility can be declared only after downstream consumers prove the
API shape in real integration workflows.
