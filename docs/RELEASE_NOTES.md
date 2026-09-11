# Release Notes

## 0.10.0 Public Preview Release Candidate

`0.10.0` is an unpublished release candidate for both
`io.github.dtkmn:mcp-gateway-core` and
`io.github.dtkmn:mcp-gateway-spring-webflux`. The modules remain versioned
together. `0.9.0` remains the latest published release; public dependency
examples stay on that version until both new coordinates are published and
verified from Maven Central. Release validation, independent approval, and
manual publication are still required.

### Spring WebFlux Tool-Call Handling

- Add the optional `toolRegistry(McpToolRegistry)` filter-builder setting. The
  host supplies the existing core registry containing exactly its registered,
  enabled tools. No additional catalog or product-specific tool mode is added.
- With that registry configured, check availability for well-formed tool calls
  before permissions or abuse protection. Unknown and disabled tools receive
  the same HTTP `200`
  JSON-RPC `-32602` response with the generic `Unknown tool` message and the
  original request id; neither request reaches tool execution.
- Preserve HTTP `403` for enforced scope denials on active tools. An active
  tool rejected by enforced authorization because its permission mapping is
  missing receives a generic JSON-RPC `-32603` internal error without exposing
  registry or policy details.
- Reject explicit invalid tool-call ids with HTTP `400` and JSON-RPC `-32600`.
  Accept id-less tool-call notifications with HTTP `202` without executing a
  tool. Ordinary notifications and recognized JSON-RPC response envelopes keep
  their existing downstream handling.
- Keep registry-aware filtering active even when authorization and protection
  are disabled. Existing constructors and builder configurations without a
  registry retain their previous behavior.

This handling is opt-in: upgrading the library alone does not wire a host's
active tools into the filter. Hosts must keep discovery, dispatch, and complete
permission mappings consistent, and run authentication before the governance
filter. An existing `McpToolAccessRegistry.toolRegistry()` can be reused when
its access rules were validated against the active tool set. The adapter does
not discover tools or configure ZAP or Spring AI runtimes. See the
[active-tool registry contract](CONTRACT_REFERENCE.md#active-tool-registry-unreleased)
for the response and integration details.

### Core Rate-Limiter API

- Add `TokenBucketRateLimiter.attempt(String, Policy)` and its public `Attempt`
  result, returning the allow/deny decision and retry delay from the same
  consumption attempt instead of requiring a separate retry-delay lookup.
- Allowed attempts report zero retry delay. Rejected attempts report at least
  one second, including the fallback when a new key cannot be admitted because
  the tracked-key limit is full.
- Retain the existing `tryConsume` and `retryAfterSeconds` APIs. The core remains
  framework-neutral with no runtime dependencies. The tool-availability fix
  does not change the core authorization engine.

Callers opt into the combined result by using `attempt`; existing governance
flows are not automatically switched to the new API.

### Dependency Updates

- Update the adapter's published dependencies to Spring Framework `7.0.9`,
  Spring Security `7.1.1`, and Jackson `3.1.6`; the Spring Framework update also
  brings Reactor Core `3.8.7`.
- Align the integration-test stack with Spring Boot `4.1.1`, Spring AI `2.0.1`,
  Reactor test support `3.8.7`, Reactor Netty `1.3.7`, and Netty `4.2.17.Final`.
  MCP Java SDK remains `2.0.0`. Spring Boot, Spring AI, MCP SDK, and Netty are
  test dependencies here, not new published adapter dependencies.
- These patch updates address the dependency versions flagged during this
  release's Snyk review. Consuming applications must also update and verify
  their own runtime dependency management; this repository's test-dependency
  updates do not upgrade a consuming server's runtime or configure its wiring.

### Build And Release Verification

- Update the Gradle Wrapper to `9.7.1` and consolidate development and release
  preparation around `verifyGatewayDevelopment`, followed by clean Java 17
  consumer checks against the staged publications.
- Retain Java 17 compilation targets, module-owned JAR class checks, the core
  `java.base`-only dependency check, and adapter runtime bytecode checks.
  Signing and Central bundle validation remain in the guarded release script.
- Separate successful Snyk scanning/uploading from the vulnerability result:
  the `Snyk vulnerabilities` commit status fails when findings remain even if
  the scan workflow itself completed successfully.

## 0.9.0 Public Preview

`0.9.0` is the latest published version of both public-preview artifacts and
supersedes `0.8.0`. The core library and optional Spring WebFlux adapter are
available from Maven Central at `io.github.dtkmn:mcp-gateway-core:0.9.0` and
`io.github.dtkmn:mcp-gateway-spring-webflux:0.9.0`. The published source commit
is `46b0ec29e43466c9aea5251c53bcc2f0f6a96f58`.

### Spring WebFlux Adapter Configuration

- Add a fluent `McpGatewayWebFluxGovernanceFilter` builder with required
  `JsonMapper` and `McpGatewayWebFluxContextResolver` inputs, named optional
  collaborators, and paired callbacks for dynamic authorization and protection.
- Require at least one authorization or protection evaluator before `build()`
  succeeds. Existing defaults and public constructors remain unchanged.
- Keep Spring bean registration and policy decisions in the consuming runtime.

## 0.8.0 Public Preview

`0.8.0` supersedes `0.7.2`. The core library and optional Spring WebFlux adapter are
available from Maven Central at `io.github.dtkmn:mcp-gateway-core:0.8.0` and
`io.github.dtkmn:mcp-gateway-spring-webflux:0.8.0`. The published source commit
is `2f533f76032848e6d4abcef1144b008144f8e136`.

### API And Binary Deltas

Breaking changes included in this release:

- Replace the Spring WebFlux adapter's Jackson 2 dependency
  (`com.fasterxml.jackson.core:jackson-databind`) with the Jackson 3.1 LTS line
  (`tools.jackson.core:jackson-databind:3.1.5`).
- Replace Jackson 2 `ObjectMapper` parameters on all public
  `McpGatewayWebFluxGovernanceFilter` constructors and the
  `McpJsonRpcToolInvocationParser` constructor with Jackson 3
  `tools.jackson.databind.json.JsonMapper`.
- Require existing adapter consumers to migrate their imports, mapper bean, and
  constructor wiring. For Spring Boot 4.1 applications, inject Boot's
  auto-configured Jackson 3 `JsonMapper`. Standalone consumers can create one
  with `JsonMapper.builder().build()`.

The Jackson type change alters JVM constructor descriptors and is intentionally
neither source- nor binary-compatible with `0.7.2`. It is released as `0.8.0`
rather than presented as a backward-compatible `0.7.x` patch.

The `mcp-gateway-core` implementation, public API, Java 17 target, and empty
runtime dependency graph are unchanged; its version moves in lockstep because
the two modules are published as one release.

### Spring Platform Upgrade

- Align the adapter test platform with Spring Boot `4.1.0`, Spring AI `2.0.0`,
  and MCP Java SDK `2.0.0`.
- Update Spring Security to `7.1.0` and Reactor test support to `3.8.6`.
- Exercise the MCP `2025-11-25` protocol and the Jackson 3 MCP JSON mapper in
  the server-initiated keep-alive response regression test.

## 0.7.2 Public Preview

`0.7.2` was the preceding published version of both public-preview artifacts.
The core library and optional Spring WebFlux adapter remain available from
Maven Central at `io.github.dtkmn:mcp-gateway-core:0.7.2` and
`io.github.dtkmn:mcp-gateway-spring-webflux:0.7.2`. The published source commit
is `c2f3eac20c3348fe48881cf19196edcf93e680eb`.

### Spring WebFlux Adapter Correctness

- Restore the response pass-through required for server-initiated Streamable
  HTTP requests by distinguishing response envelopes from requests on the shared
  MCP `POST` endpoint. Responses to server-initiated ping, roots, sampling,
  elicitation, and other requests now reach the downstream MCP runtime instead
  of being rejected for a missing `method`.
- Keep the response path narrow: recognized envelopes require no `method`, a
  string or numeric `id`, and exactly one of `result` or `error`. Any request
  carrying a `method` remains governed even if response fields are also present.
  Duplicate fields, case-variant governance fields, batch bodies, and objects
  that fail this minimal discriminator retain fail-closed behavior. Full
  JSON-RPC response validation and correlation remain downstream concerns.
- Skip request context, scope extraction, authorization, and action-based abuse
  protection for response envelopes while preserving the message body, session
  headers, configured body-size limit, surrounding security chain, and
  downstream protocol/session validation.

There were no public API changes in this patch.

## 0.7.1 Public Preview

`0.7.1` was the preceding public-preview release of both artifacts. The core
library and optional Spring WebFlux adapter remain available from Maven Central
at `io.github.dtkmn:mcp-gateway-core:0.7.1` and
`io.github.dtkmn:mcp-gateway-spring-webflux:0.7.1`.

### Security And Release Integrity

- Update the WebFlux adapter's default `jackson-databind` dependency to `2.21.5`,
  the fixed 2.21.x release for CVE-2026-54515. Consumers should upgrade to
  `mcp-gateway-spring-webflux:0.7.1`; consumers temporarily held on `0.7.0`
  should override Jackson to a fixed release.
- Pin the Gradle 9.6.1 distribution checksum and validate the checked-in Gradle
  Wrapper in every GitHub workflow that executes it.
- Pin every remote GitHub Action and reusable workflow to a reviewed full
  commit SHA, and enable the repository setting that rejects non-SHA action
  references.
- Enforce action pinning by structurally parsing the decoded YAML job and step
  `uses` nodes instead of matching raw text. The fail-closed check rejects
  duplicate or invalid workflow shapes, aliases or merges that conceal action
  references, and local action references until recursive local-manifest
  inspection is implemented.
- Protect release signing and Central credentials with the main-only
  `central-validation-upload` environment. A trusted reviewer distinct from the
  workflow dispatcher must approve each run, self-review is prevented,
  administrator bypass is disabled, and the credentials exist only as
  environment secrets.
- Refuse Central validation uploads when either Maven coordinate already exists,
  preventing an immutable release version from being reused.
- Accept release signatures made by a signing subkey while still requiring the
  configured full primary-key fingerprint.
- Run both clean Java 17 consumer checks against the exact non-snapshot staging
  repository inside the guarded Central path, before artifacts are signed or
  uploaded.
- Verify timestamped snapshot JAR, POM, sources, Javadocs, and checksums for both
  modules from Maven metadata in the development gate.

### Core Correctness

- Make token-bucket creation and stale eviction race-safe under concurrent keys,
  honor `maxTrackedKeys` values down to `1`, saturate stale-age arithmetic, and
  prevent policy changes from retroactively crediting tokens at a new refill
  rate.
- Keep MCP tool selectors exact and case-sensitive, reject malformed empty
  subdomain matches such as `.example.com` for `*.example.com`, and make
  overnight policy-window days refer to the day the window starts.
- Harden `UrlScope` against authority/path parser differentials by validating
  original raw and decoded URI components before normalization, then rejecting
  user information, encoded backslashes and controls, multiply encoded paths,
  malformed percent-encoded UTF-8, malformed ports, and raw Unicode hosts while
  accepting normalized ASCII and punycode host names.
- Normalize direct `McpAbuseProtectionDecision` construction so allow decisions
  cannot carry rejection fields and rejected decisions always have safe fallback
  codes, reasons, and retry delays.
- Require configured authorization scopes to be valid RFC 6749 scope tokens.

### Spring WebFlux Adapter Correctness

- Match the configured endpoint relative to the application context and compare
  matrix-parameterized path segments by route value, closing context-path and
  matrix-parameter governance bypasses without broadening matches to subpaths.
- Restrict request-size recovery to the adapter's own body read so a downstream
  `DataBufferLimitException` propagates instead of being rewritten as an adapter
  `413` response.
- Reject duplicate JSON object fields and whitespace-padded method/tool
  identifiers while governance is active, avoiding authorization/execution
  parser differentials.
- Sanitize correlation headers, fall back through the configured resolver when a
  context has no correlation id, ignore unauthenticated/anonymous or blank
  Spring scopes, and omit unsafe scope values from `WWW-Authenticate`.
- Release cached request buffers reliably, replace conflicting transfer framing
  when replaying a body, normalize null scopes from custom extractors, and defer
  filter work so observer failures remain reactive and fail closed.

### Verification

The release code passed development CI, CodeQL, Snyk, the public-preview
publication proof, both clean Java 17 consumer checks, and the documentation
build. Independently approved dry-run and validation-upload jobs from exact
source commit `0f5fe82da70fc335f0e0fc9e93833621077d1064` imported only the
environment-scoped signing key, built and signed both `0.7.1` modules, and
verified the closed-world bundle and detached signatures before upload.

After manual publication, both coordinates and all 48 expected POM, binary,
sources, Javadocs, checksum, and detached-signature files were retrieved from
Maven Central and matched the approved upload bundle byte for byte. Signatures
resolved to primary fingerprint
`CC460079AB0687AC3DBB96DDE15DFDE144C104C1`, Maven metadata reported `0.7.1` as
the latest release for both modules at that time, and a fresh external Java 17
consumer resolved and ran both artifacts using Maven Central as its only
repository.

There are no new public API/binary deltas in this patch.

## 0.7.0 Public Preview

`0.7.0` is a hardening release for public-preview consumers. It ships WebFlux
fail-closed request-shape behavior.

### API And Binary Deltas

Accepted compatible additions:

- `mcp-gateway-spring-webflux` adds `McpInvalidRequestObserver` so runtimes can
  observe adapter-level invalid request rejections without receiving request
  payloads. Observer arguments are `reason`, server HTTP `requestId`, and
  resolved `correlationId`; request payloads are never echoed.
- `McpGatewayWebFluxGovernanceFilter` adds an overload that accepts
  `McpInvalidRequestObserver`. Existing constructors remain available and use a
  no-op observer.

<a id="0.7.0-behavior-clarifications"></a>
### Behavior Clarifications

WebFlux request-shape handling is fail-closed when governance is active.
Governance is active when authorization policy is enabled or abuse protection is
enabled. The adapter rejects malformed JSON, unsupported JSON-RPC batches,
non-object requests, missing or invalid `method`, and invalid
`tools/call.params.name` before principal lookup, context resolution, scope
extraction, authorization, protection, or downstream body replay.

JSON-RPC batch arrays are unsupported by the governance adapter when governance
is active and return `400` with reason `batch_not_supported`. Batches are not
universally rejected by the transport: when both authorization and protection
governance are inactive, the adapter leaves the request body untouched and
passes batch bodies downstream unchanged.

Invalid request responses use adapter JSON, not a JSON-RPC error envelope:
HTTP `400`, a JSON-compatible `Content-Type`, `error` value
`invalid_json_rpc_request`, one of the stable low-cardinality `reason` values,
ISO-8601 `timestamp`, resolved `correlationId`, and the server HTTP request id
as `requestId`. JSON-RPC `id` is not reflected as `requestId` and no request
payload is echoed. Oversized bodies are rejected with HTTP `413` and
`request_body_too_large` only when governance is active; with governance
inactive, the adapter preserves exact downstream pass-through behavior.

The adapter does not require or validate the JSON-RPC `jsonrpc` version field
for `0.7.0`. Valid non-tool JSON-RPC methods remain non-authorizable:
authorization is skipped for them, while abuse protection still runs when
enabled.

Behavior clarifications are recorded in release notes.

<a id="0.7.0-verification"></a>
### Verification

Publication verification passed for this release. The current commands for
build, consumer, and documentation verification are:

```bash
./gradlew verifyGatewayDevelopment --no-daemon --stacktrace --warning-mode fail
./bin/java17-consumer-smoke.sh
npm --prefix docs-site run build
```
