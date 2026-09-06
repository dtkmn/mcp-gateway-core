# Security Policy

## Supported Versions

`mcp-gateway-core` and `mcp-gateway-spring-webflux` are public preview.
`0.9.0` is the latest published release and the only line expected to receive
security fixes.

Older public-preview versions may be replaced instead of patched if the safest
fix requires changing a contract.

## Reporting A Vulnerability

Use [GitHub private vulnerability reporting](https://github.com/dtkmn/mcp-gateway-core/security/advisories/new)
to contact the maintainers. Include the affected version, impact, and a minimal
reproduction. Do not open a public issue with exploit details, proof-of-concept
payloads, private keys, tokens, or customer data.

## Scope

In scope:

- vulnerabilities in the public `mcp.gateway.core` Java contracts and helpers;
- dependency vulnerabilities affecting the published artifact or test/release
  gates;
- release, signing, or artifact-shape issues that could cause consumers to
  trust the wrong artifact.

Out of scope:

- downstream runtime vulnerabilities that live outside this repository;
- scanner, ZAP, report, finding, queue, or application-server behavior;
- generic denial-of-service reports that do not identify a concrete failure in
  this library.

## Security Tooling

The repository uses GitHub-native checks first:

- Dependabot version updates for GitHub Actions, Gradle, and npm;
- checked-in third-party GitHub Actions and reusable workflows use reviewed
  full-length commit SHAs;
- repository settings enforce full-SHA pinning;
- Gradle distribution checksum pinning and Wrapper JAR validation before CI
  workflows execute the build;
- CodeQL Java analysis with an explicit Gradle test build;
- the Gradle development gate for JAR class ownership, core `jdeps`, and Java
  17 compatibility checks for adapter runtime dependencies, followed by clean
  Java 17 consumer smoke tests against the staged publications;
- the Central validation upload workflow for artifact signatures, the configured
  signer fingerprint, and checksums from the final combined bundle. Its job is
  bound to the protected `central-validation-upload` environment. Release refs are restricted to
  `main` only. At least one required reviewer must be distinct from the workflow
  dispatcher. Self-review is prevented. Administrator bypass is disabled.
  Release credentials exist only as environment secrets; Central Portal and GPG
  credentials are never stored as repository secrets.

The repository also has an explicit Snyk Open Source workflow for the Gradle
project graph. That workflow requires:

- `SNYK_TOKEN` as a repository or organization secret.

`SNYK_ORG` may be set as a repository or organization secret, or as a variable
when the scan must be tied to a specific Snyk organization. If it is absent,
Snyk uses the default organization associated with `SNYK_TOKEN`.

Fork pull requests skip the Snyk job because GitHub does not provide repository
secrets to those runs. CI and CodeQL still run on pull requests to `main`.
Snyk runs on same-repository pull requests, pushes to `main`, its schedule, and
manual dispatches; these runs fail if `SNYK_TOKEN` is missing. Dependabot runs
use the separately configured Dependabot secrets.

Snyk results are uploaded as SARIF for GitHub Code Scanning and as a workflow
artifact. The `Snyk vulnerabilities` commit status fails for any dependency
findings. A completed scan and successful upload leave the workflow green;
scanner, configuration, and upload failures leave it red. This keeps GitHub's
tool-health status separate from the vulnerability result.

Snyk project import, dashboard ownership, alert triage, ignores, and
monitor snapshots remain manual repository or organization responsibilities.
