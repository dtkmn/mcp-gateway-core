# Release Notes

## 0.7.2 (Unreleased)

`0.7.2-SNAPSHOT` is the current development version on `develop`. `0.7.1` is
the latest published version and remains the version used by public dependency
examples.

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

There are no new public API/binary deltas in this patch.

## 0.7.1 Public Preview

`0.7.1` is the latest published version of both public-preview artifacts. The
core library and optional Spring WebFlux adapter are available from Maven
Central at `io.github.dtkmn:mcp-gateway-core:0.7.1` and
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
the latest release for both modules, and a fresh external Java 17 consumer
resolved and ran both artifacts using Maven Central as its only repository.

There are no new public API/binary deltas in this patch.

## 0.7.0 Public Preview

`0.7.0` is a hardening release for public-preview consumers. It adds
compatibility gates first, then ships WebFlux fail-closed request-shape behavior
behind those gates.

<a id="0.7.0-api-binary-deltas"></a>
### API And Binary Deltas

Accepted compatible additions:

- `mcp-gateway-spring-webflux` adds `McpInvalidRequestObserver` so runtimes can
  observe adapter-level invalid request rejections without receiving request
  payloads. Observer arguments are `reason`, server HTTP `requestId`, and
  resolved `correlationId`; request payloads are never echoed.
- `McpGatewayWebFluxGovernanceFilter` adds an overload that accepts
  `McpInvalidRequestObserver`. Existing constructors remain available and use a
  no-op observer.

Future API or binary deltas must be listed in
`compatibility/accepted-api-deltas-0.7.0.json` and linked to this file with an
explicit release-notes anchor. API and binary delta classifications are limited
to `compatible-addition` and `breaking-change`. Breaking changes must include
structured maintainer approval with `approver`, `url`, and `approvedAt`;
compatible additions must use `null` maintainer approval. Each delta `symbol`
is the owning class and every recorded signature must start with
`<symbol> :: `.

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

Behavior clarifications belong in release notes, not in the accepted API/binary
delta registry.

<a id="0.7.0-verification"></a>
### Verification

Publication verification passed:

```bash
./gradlew verifyGatewayPublicPreviewPublication --no-daemon --stacktrace --warning-mode fail
./bin/java17-consumer-smoke.sh
./bin/java17-source-compat-0.6-consumer.sh
npm --prefix docs-site run build
```
