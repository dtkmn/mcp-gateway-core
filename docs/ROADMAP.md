# Roadmap

This roadmap is intentionally conservative. `mcp-gateway-core` should become a
trustworthy MCP governance library before it tries to become anything larger.

## Current Stage: Public Preview

The current artifact is usable by early consumers, but it is not a stable API
promise yet.

Current focus:

- keep the package and Maven coordinate steady;
- prove the contracts through real downstream consumers;
- keep the core artifact JDK-only;
- keep framework support in separate adapter artifacts;
- reject scanner, runtime, private, and data-plane coupling;
- publish only artifacts that pass the public-preview verification gate.

## Near-Term Work

1. **Consumer proof**

   Keep downstream runtime builds consuming the published artifact, not copied
   source. The first proof target is the ZAP/security runtime, but the core
   API must stay MCP-neutral.

2. **Contract hardening**

   Add source-compatibility and behavior regression checks for the public
   contract families that downstream consumers actually use.

3. **Adapter proof**

   Prove the Spring WebFlux adapter against real downstream MCP runtimes without
   letting framework dependencies leak into `mcp-gateway-core`.

4. **Documentation quality**

   Keep the module map, compatibility policy, release policy, and security
   policy aligned with shipped code. Do not add marketing pages until there is
   a runtime behavior or consumer proof behind the claim.

5. **Security hygiene**

   Run GitHub-native dependency and code scanning, keep release signing gates
   strict, and add external scanning only when ownership and token management
   are explicit.

## Graduation Criteria For Stable API

Do not call this stable until all of these are true:

- at least two downstream consumers use the published artifact without source
  copying;
- breaking-change detection exists for public contracts;
- release notes clearly distinguish compatible additions from breaking changes;
- Javadocs are clean enough for public API users;
- security scanning and release-signing gates are required in CI;
- adapter artifacts have their own closed-world and forbidden-coupling gates;
- the compatibility policy defines what SemVer means for this library.

## Non-Goals

This repository is not trying to become:

- a proxy or traffic data plane;
- a Kubernetes Gateway API implementation;
- an agent runtime;
- a scanner integration layer;
- a policy engine with its own language;
- a marketplace or plugin loader.

Those may be valid products. They are not this library's job.
