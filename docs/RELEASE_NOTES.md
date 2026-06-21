# Release Notes

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
