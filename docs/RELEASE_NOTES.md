# Release Notes

## 0.7.0 Public Preview

`0.7.0` is a hardening release for public-preview consumers. The release goal is
to add compatibility gates first, then land WebFlux fail-closed request-shape
behavior behind those gates.

<a id="0.7.0-api-binary-deltas"></a>
### API And Binary Deltas

No accepted API or binary deltas have been recorded yet.

Future API or binary deltas must be listed in
`compatibility/accepted-api-deltas-0.7.0.json` and linked to this file with an
explicit release-notes anchor. API and binary delta classifications are limited
to `compatible-addition` and `breaking-change`.

<a id="0.7.0-behavior-clarifications"></a>
### Behavior Clarifications

No behavior clarifications have been recorded yet.

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
