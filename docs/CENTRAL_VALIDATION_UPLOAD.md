# Central Validation Upload

This runbook prepares and optionally uploads `io.github.dtkmn:mcp-gateway-core`
to Sonatype Central as a `USER_MANAGED` deployment.

It does not publish the deployment. Publishing must remain a deliberate manual
Portal action until the release policy says otherwise.

## Required Secrets

Configure these in the `dtkmn/mcp-gateway-core` GitHub repository before running
the workflow:

- `GATEWAY_CORE_RELEASE_GPG_PRIVATE_KEY_B64`
- `GATEWAY_CORE_RELEASE_GPG_FINGERPRINT`
- `GATEWAY_CORE_RELEASE_GPG_PASSPHRASE` if the key is passphrase-protected
- `CENTRAL_PORTAL_USERNAME`
- `CENTRAL_PORTAL_PASSWORD`

The Central credentials must be a Central Portal user token.

## Dry Run

Run the `Central Validation Upload` workflow with:

- `execute_upload`: `false`
- `upload_confirmation`: blank

The dry run imports the release key, builds the public-preview proof, signs the
staged artifacts, creates a closed-world Central bundle, and verifies checksums
and detached signatures from the extracted ZIP payload.

The dry run prints a confirmation token like:

```text
upload:io.github.dtkmn:mcp-gateway-core:0.5.5:USER_MANAGED
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

After upload, inspect the deployment in the Central Portal. If it reaches
`VALIDATED`, the artifact is available for manual validation and can be
published manually later. Do not publish unless the project owner explicitly
approves publishing that version.
