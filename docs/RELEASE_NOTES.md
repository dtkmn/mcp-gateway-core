# Release Notes

## 0.7.0 Public Preview

`0.7.0` is a hardening release for public-preview consumers. The release goal is
to add compatibility gates first, then land WebFlux fail-closed request-shape
behavior behind those gates.

<a id="0.7.0-api-binary-deltas"></a>
### API And Binary Deltas

Accepted compatible additions:

- `mcp-gateway-spring-webflux` adds `McpInvalidRequestObserver` so runtimes can
  observe adapter-level invalid request rejections without receiving request
  payloads.
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

WebFlux request-shape handling is fail-closed when governance is active. The
adapter rejects malformed JSON, unsupported JSON-RPC batches, non-object
requests, missing or invalid `method`, and invalid `tools/call.params.name`
before principal lookup, context resolution, authorization, protection, or
downstream body replay. It does not require or validate the JSON-RPC `jsonrpc`
version field for `0.7.0`. When both authorization and protection governance are
inactive, the adapter leaves request bodies untouched and passes them downstream.

Behavior clarifications belong in release notes, not in the accepted API/binary
delta registry.

<a id="0.7.0-verification"></a>
### Verification

Before publishing, the release must pass:

```bash
./gradlew verifyGatewayPublicPreviewPublication --no-daemon --stacktrace --warning-mode fail
./bin/java17-consumer-smoke.sh
npm --prefix docs-site run build
```
