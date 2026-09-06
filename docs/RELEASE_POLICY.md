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

`0.9.0` is the latest published public-preview version. It adds a compatible
fluent builder to the WebFlux adapter while retaining existing constructors and
behavior. The framework-neutral core API is unchanged.

## Release Gates

Development CI and release preparation use the same Gradle gate:

```bash
./gradlew verifyGatewayDevelopment --no-daemon --stacktrace --warning-mode fail
```

This runs tests, checks JAR class ownership, requires core `jdeps` to report
only `java.base`, and checks adapter runtime dependencies for Java 17
compatibility. Compilation targets Java 17 with `--release 17`. Each JAR must
contain classes in its module's package and no classes outside that package;
normal resources, including license files and service descriptors, are allowed.

The gate stages both Maven publications, including POMs, binaries, sources, and
Javadocs. The command fails on Gradle deprecations. The Gradle distribution
checksum is pinned, and CI separately validates the checked-in Wrapper JAR
before executing it.

CI and release preparation must then run `bin/java17-consumer-smoke.sh` with
Java 17. It compiles and runs separate clean downstream consumers: one that
depends only on staged `mcp-gateway-core`, and one that depends on staged
`mcp-gateway-spring-webflux` and its published transitive API dependencies.
The gate and smoke test work with either the development snapshot or the
selected release version.

The separate Snyk workflow is an external dependency scan for the Gradle
project graph. Fork pull requests skip this secret-dependent job; CI and CodeQL
still run. Snyk is enforced on enabled runs: missing `SNYK_TOKEN`
fails the job, Snyk findings fail the job after SARIF upload, and results
remain reviewable through GitHub Code Scanning or the SARIF artifact. `SNYK_ORG`
is optional, may be supplied as a secret or variable, and only pins the scan to
a specific Snyk organization. The workflow does not upload artifacts to Central,
publish releases, create Snyk monitor snapshots, or replace the build and
consumer checks above.

Before uploading public-preview artifacts to Central for validation, the guarded
upload path must pass:

```bash
./bin/gateway-public-preview-central-validation-upload.sh
```

That command requires a non-snapshot version and uses the configured release
GPG key to create a signed bundle containing `mcp-gateway-core` and
`mcp-gateway-spring-webflux`. It checks the expected artifacts and checksums
from the extracted ZIP payload, verifies each signature against the configured
signer, and prints the exact confirmation token required for an optional
`USER_MANAGED` validation upload. Before signing or uploading, it
also requires a JDK 17 (through `GATEWAY_CORE_JAVA17_HOME` when necessary) and
runs the downstream consumer smoke test against the exact release-version
staging repository.

The GitHub validation-upload job must use the protected
`central-validation-upload` environment. The environment is operational only
when all of these controls hold:

- release refs are restricted to `main` only, with no release-branch,
  wildcard-branch, or tag exceptions;
- at least one required reviewer must be distinct from the workflow dispatcher;
- self-review is prevented by enabling **Prevent self-review**;
- administrator bypass is disabled by clearing **Allow administrators to bypass
  configured protection rules**;
- release credentials exist only as environment secrets, with no GPG or Central
  repository- or organization-level duplicates that bypass the gate.

Repository settings must also require full-length commit SHA references for
GitHub Actions. New third-party action and reusable-workflow references must be
pinned to reviewed full commit SHAs.

## Publishing Boundary

Publishing is manual until this policy says otherwise. A validation bundle is
not a release. A Central Portal deployment is not public until it is explicitly
published in the Portal.

Outside the short release-cut window, `develop` uses the next `-SNAPSHOT`
version. It may temporarily carry the reviewed non-snapshot candidate while
that candidate is promoted to `main`; the post-publication sequence below must
restore the snapshot state immediately. Release preparation must deliberately
select an unpublished, non-snapshot version; the guarded upload path rejects
snapshots and refuses to upload a coordinate that already exists on Maven
Central.

Once a version is published to Maven Central, the same coordinate and version
must never be reused.

The required release sequence is:

1. prepare the non-snapshot candidate on a review branch from `main`, then merge
   it to `main` after required review and CI;
2. run an independently approved `execute_upload=false` dry run and record its
   exact source SHA and confirmation token;
3. if `main` is unchanged, run an independently approved
   `execute_upload=true` validation upload with that token;
4. wait for `VALIDATED`, then require a separate explicit project-owner decision
   before using Central Portal's manual publish action;
5. after Central reports publication, verify both Maven coordinates and their
   artifacts from a clean consumer;
6. tag the exact uploaded source commit, create the GitHub Release, and finalize
   public release notes and dependency examples;
7. synchronize `develop` and advance it to the next `-SNAPSHOT` version.

The detailed operator checklist is in
[`CENTRAL_VALIDATION_UPLOAD.md`](CENTRAL_VALIDATION_UPLOAD.md). No step may infer
publication from a successful dry run, validation upload, deployment id, or
`VALIDATED` state.
