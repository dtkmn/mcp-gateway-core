# Central Validation Upload

This runbook prepares and optionally uploads the public-preview MCP Gateway
artifacts to Sonatype Central as one `USER_MANAGED` deployment:

- `io.github.dtkmn:mcp-gateway-core`
- `io.github.dtkmn:mcp-gateway-spring-webflux`

It does not publish the deployment. Publishing must remain a deliberate manual
Portal action until the release policy says otherwise.

## Required Secrets

Before running the workflow, create a GitHub environment named
`central-validation-upload`. Require reviewer approval, prevent self-review, and
restrict deployment branches or tags to the refs used by the release process.
The workflow job is bound to that environment and must not run with unprotected
repository-level release credentials.

Configure these as **environment secrets** on `central-validation-upload`:

- `GATEWAY_CORE_RELEASE_GPG_PRIVATE_KEY_B64`
- `GATEWAY_CORE_RELEASE_GPG_FINGERPRINT`
- `GATEWAY_CORE_RELEASE_GPG_PASSPHRASE` if the key is passphrase-protected
- `CENTRAL_PORTAL_USERNAME`
- `CENTRAL_PORTAL_PASSWORD`

Do not duplicate these as repository or organization secrets that are available
without the environment approval gate. In the repository Actions settings, also
require actions to be pinned to a full-length commit SHA. Every third-party
action in the checked-in workflows is pinned to a reviewed commit; version
comments are informational and are not executable references.

The Central credentials must be a Central Portal user token.

`GATEWAY_CORE_RELEASE_GPG_FINGERPRINT` must be the full primary-key fingerprint.
Signatures made by one of that primary key's signing subkeys are accepted.

Set `GATEWAY_CORE_JAVA17_HOME` to a JDK 17 installation when the current
`JAVA_HOME` is not JDK 17. The GitHub workflow installs JDK 17, captures its
home, and then restores JDK 25 for the main release build.

Before starting a release dry run, set `gatewayCoreVersion` to the intended,
unpublished, non-snapshot version and update the matching release metadata. The
normal development version is a `-SNAPSHOT` and is deliberately rejected by this
script.

## Dry Run

Run the `Central Validation Upload` workflow with:

- `execute_upload`: `false`
- `upload_confirmation`: blank

The dry run imports the release key, builds the public-preview proof, compiles and
runs both clean Java 17 downstream consumers against the release-version staged
artifacts, signs those artifacts, creates a closed-world Central bundle, and
verifies checksums and detached signatures from the extracted ZIP payload.

The dry run prints a confirmation token like:

```text
upload:io.github.dtkmn:mcp-gateway-public-preview:<version>:USER_MANAGED
```

## Validation Upload

Run the same workflow with:

- `execute_upload`: `true`
- `upload_confirmation`: the exact token printed by dry-run mode

The workflow uploads the verified bundle to:

```text
https://central.sonatype.com/api/v1/publisher/upload
```

with `publishingType=USER_MANAGED`.

Before uploading, the script checks both coordinates on Maven Central and fails
closed if either version is already published. Maven coordinates are immutable;
choose a new version instead of attempting to replace an existing artifact.

After upload, inspect the deployment in the Central Portal. If it reaches
`VALIDATED`, the artifact is available for manual validation and can be
published manually later. Do not publish unless the project owner explicitly
approves publishing that version.
