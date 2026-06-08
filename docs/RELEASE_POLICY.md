# MCP Gateway Core Release Policy

This repository publishes the MCP-neutral `mcp.gateway.core` Java API and
optional framework adapter artifacts, starting with `mcp.gateway.spring.webflux`.
It does not publish a gateway runtime, scanner pack, application server, or
traffic-management data plane.

## Current Stage

`mcp-gateway-core` and `mcp-gateway-spring-webflux` are public preview.

Public preview means:

- artifacts may be validated and consumed by early downstream integrations;
- package names and Maven coordinates should not churn casually;
- source and binary compatibility are not yet guaranteed across minor changes;
- breaking changes are allowed while the API is still proving its real users.

Do not describe these artifacts as stable until this policy is updated and a
stable release gate exists.

## Release Gates

Before publishing any public-preview artifact, CI must pass:

```bash
./gradlew verifyGatewayPublicPreviewPublication --no-daemon --stacktrace
```

That gate proves:

- unit tests pass;
- the core JAR contains only `mcp/gateway/core/**` classes and manifest metadata;
- `jdeps` reports only `java.base`;
- adapter JARs contain only their adapter package classes and manifest metadata;
- forbidden downstream runtime and product-specific markers are absent;
- Maven metadata has required POM fields;
- the Central Portal ZIP is closed-world;
- checksums match the ZIP payload;
- the signed dry-run ZIP verifies detached signatures from extracted payloads.

Before uploading the core artifact to Central for validation, the guarded
core-upload path must pass:

```bash
./bin/gateway-core-central-validation-upload.sh
```

That command uses the configured release GPG key, creates a release-signed
bundle, verifies the extracted ZIP payload, and prints the exact confirmation
token required for an optional `USER_MANAGED` validation upload.

The current guarded upload script is scoped to `mcp-gateway-core`. Adapter
artifacts must not be uploaded through that script. Publish an adapter only
after this repository has an equivalent release-key validation upload path or a
deliberately reviewed manual upload runbook for that adapter.

## Publishing Boundary

Publishing is manual until this policy says otherwise. A validation bundle is
not a release. A Central Portal deployment is not public until it is explicitly
published in the Portal.

Once a version is published to Maven Central, the same coordinate and version
must never be reused.
