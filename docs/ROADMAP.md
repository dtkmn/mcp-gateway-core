# Roadmap

This roadmap is intentionally conservative. `mcp-gateway-core` should become a
trustworthy MCP governance library before it tries to become anything larger.

## Current Stage: Public Preview

The current artifact is usable by early consumers, but it is not a stable API
promise yet.

Current focus:

- operate and consume the independently verified `0.7.1` public-preview release
  while `0.8.0` is validated as the next public-preview release;
- keep the core artifact JDK-only and framework support in separate adapter
  artifacts;
- prove published and staged artifacts with current Java 17 core-only and
  WebFlux consumers;
- keep WebFlux request-shape, activation, pass-through, and invalid-request
  observer behavior pinned by tests;
- reject scanner, runtime, private, and data-plane coupling;
- publish only artifacts that pass the public-preview verification gate.

## Near-Term Work

1. **0.7.1 consumer adoption and 0.8.0 release validation**

   `0.7.1` is the latest published version. Both coordinates, their complete
   signed artifact sets, Maven metadata, and a fresh Maven-Central-only Java 17
   consumer have passed post-publication verification. Keep public dependency
   landing-page examples on `0.7.1`, clearly label source-tree `0.8.0` candidate
   instructions, gather evidence from real consumers, and validate the release
   candidate with its explicit Jackson 3 adapter migration. Require downstream
   adapter consumers to prove their new `JsonMapper` wiring. Treat any
   disagreement between public docs and Central as a release-blocking
   documentation defect.

2. **Consumer proof**

   Keep downstream runtime builds consuming the published artifact, not copied
   source. The core API must stay MCP-neutral even when the first consumer is a
   security runtime.

3. **Contract hardening**

   Expand source, binary, and behavior regression checks only around public
   contract families that downstream consumers actually use.

4. **Adapter proof**

   Prove the Spring WebFlux adapter against real downstream MCP runtimes without
   letting framework dependencies leak into `mcp-gateway-core`.

5. **Documentation quality**

   Keep the module map, compatibility policy, release policy, and security
   policy aligned with shipped code. Do not add marketing pages until there is
   a runtime behavior or consumer proof behind the claim.

6. **Security hygiene**

   Run GitHub-native dependency and code scanning, keep release signing gates
   strict, and add external scanning only when ownership and token management
   are explicit.

## Graduation Criteria For Stable API

Do not call this stable until all of these are true:

- at least two downstream consumers use the published artifact without source
  copying;
- breaking-change detection continues to cover public/protected contracts;
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
