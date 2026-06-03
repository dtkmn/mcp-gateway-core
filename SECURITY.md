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

Snyk or another external scanner can be added later, but it should be wired as
an explicit repository/org decision with the required token and ownership. Do
not add a token-shaped workflow that silently skips on public contributors and
pretends to be a release gate.
