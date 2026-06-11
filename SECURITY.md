# Security Policy

## Supported Versions

`mcp-gateway-core` is public preview. The latest published `0.5.x` artifact is
the only line expected to receive security fixes.

Older public-preview versions may be replaced instead of patched if the safest
fix requires changing a contract.

## Reporting A Vulnerability

Use GitHub private vulnerability reporting or a draft GitHub Security Advisory
for this repository when available. Do not open a public issue with exploit
details, proof-of-concept payloads, private keys, tokens, or customer data.

If private reporting is not available in the GitHub UI, open a minimal public
issue asking for a private disclosure channel. Include only the affected
package name and a short impact category.

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

- Dependabot version updates for GitHub Actions and Gradle;
- CodeQL Java analysis with an explicit Gradle test build;
- the Gradle public-preview verification gate for forbidden coupling,
  closed-world JAR contents, `jdeps`, Central bundle shape, checksums, and
  signed dry-run payload validation.

The repository also has an explicit Snyk Open Source workflow for the Gradle
project graph. That workflow requires:

- `SNYK_TOKEN` as a repository or organization secret.

`SNYK_ORG` may be set as a repository or organization secret, or as a variable
when the scan must be tied to a specific Snyk organization. If it is absent,
Snyk uses the default organization associated with `SNYK_TOKEN`.

If `SNYK_TOKEN` is missing, the workflow fails. That is intentional: a skipped
external scanner is not a passing security signal. GitHub does not pass
repository secrets to pull requests from forks, so those Snyk runs fail until a
maintainer reruns the scan from a trusted branch or another trusted review path.

Snyk results are uploaded as SARIF for GitHub Code Scanning and as a workflow
artifact. Snyk project import, dashboard ownership, alert triage, ignores, and
monitor snapshots remain manual repository or organization responsibilities.
