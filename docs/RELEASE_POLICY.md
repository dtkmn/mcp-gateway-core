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

Normal development CI runs the snapshot-safe gate:

```bash
./gradlew verifyGatewayDevelopment --no-daemon --stacktrace --warning-mode fail
```

That gate runs tests, compatibility and artifact-boundary checks, and stages both
Maven publications for downstream Java 17 consumer checks. It deliberately omits
Central Portal bundles and release signing because the development version is a
snapshot.

Before publishing any public-preview artifact, a release candidate with an
unpublished, non-snapshot version must additionally pass:

```bash
./gradlew verifyGatewayPublicPreviewPublication --no-daemon --stacktrace --warning-mode fail
```

That gate proves:

- unit tests pass;
- the Gradle distribution checksum is pinned, while CI separately validates the
  checked-in Gradle Wrapper JAR before executing it;
- Gradle deprecations fail the build instead of becoming release-prep noise;
- accepted API/binary deltas are machine-readable and release-note linked;
- public/protected API signatures remain compatible with the frozen `0.6.0`
  baseline unless an intentional delta is accepted;
- the core JAR contains only `mcp/gateway/core/**` classes and manifest metadata;
- `jdeps` reports only `java.base`;
- adapter JARs contain only their adapter package classes and manifest metadata;
- published classes and adapter runtime dependencies are Java 17-compatible;
- forbidden downstream runtime and product-specific markers are absent;
- Maven metadata has required POM fields;
- the Central Portal ZIP is closed-world;
- checksums match the ZIP payload;
- the signed dry-run ZIP verifies detached signatures from extracted payloads.

CI and release preparation must also run `bin/java17-consumer-smoke.sh` and
`bin/java17-source-compat-0.6-consumer.sh` after the public-preview proof.
Those checks switch to a Java 17 runtime. The development gate stages snapshot
artifacts; the release gate stages the selected release version. The smoke test
compiles and runs separate clean downstream consumers: one that depends only on
staged `mcp-gateway-core`, and one that depends on staged
`mcp-gateway-spring-webflux` and its published transitive API dependencies. The
source-compatibility fixture compiles frozen `0.6.0` consumer source from a
temporary external Gradle project that resolves `io.github.dtkmn` artifacts
exclusively from the staged publication repository.

The API snapshot gate is scoped to public/protected members under
`mcp.gateway.core.*` and `mcp.gateway.spring.webflux.*`. It fails on
unaccepted additions, unaccepted removals, and stale accepted deltas.

The separate Snyk workflow is an external dependency scan for the Gradle
project graph. It is enforced when the workflow runs: missing `SNYK_TOKEN`
fails the job, Snyk findings fail the job after SARIF upload, and results
remain reviewable through GitHub Code Scanning or the SARIF artifact. `SNYK_ORG`
is optional, may be supplied as a secret or variable, and only pins the scan to
a specific Snyk organization. The workflow does not upload artifacts to Central,
publish releases, create Snyk monitor snapshots, or replace the public-preview
publication proof above.

Before uploading public-preview artifacts to Central for validation, the guarded
upload path must pass:

```bash
./bin/gateway-public-preview-central-validation-upload.sh
```

That command uses the configured release GPG key, creates a release-signed
bundle containing `mcp-gateway-core` and `mcp-gateway-spring-webflux`, verifies
the extracted ZIP payload, and prints the exact confirmation token required for
an optional `USER_MANAGED` validation upload. Before signing or uploading, it
also requires a JDK 17 (through `GATEWAY_CORE_JAVA17_HOME` when necessary) and
runs both downstream consumer scripts against the exact release-version staging
repository.

## Publishing Boundary

Publishing is manual until this policy says otherwise. A validation bundle is
not a release. A Central Portal deployment is not public until it is explicitly
published in the Portal.

The development branch uses the next `-SNAPSHOT` version. Release preparation
must deliberately select an unpublished, non-snapshot version; the guarded
upload path rejects snapshots and refuses to upload a coordinate that already
exists on Maven Central.

Once a version is published to Maven Central, the same coordinate and version
must never be reused.
