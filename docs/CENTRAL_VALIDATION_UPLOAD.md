# Central Validation Upload

This runbook prepares and optionally uploads the public-preview MCP Gateway
artifacts to Sonatype Central as one `USER_MANAGED` deployment:

- `io.github.dtkmn:mcp-gateway-core`
- `io.github.dtkmn:mcp-gateway-spring-webflux`

It does not publish the deployment. Publishing must remain a deliberate manual
Portal action until the release policy says otherwise.

`0.7.1` is the latest published version. `gatewayCorePublishedVersion` records
the version that public dependency examples must use; preparing or validating a
future candidate does not change it. Update that property only after both new
coordinates have propagated through Maven Central and passed the
post-publication checks below.

## Protected Release Environment

Before running the workflow, create a GitHub environment named exactly
`central-validation-upload` and maintain all of these invariants:

- Release refs are restricted to `main` only. Do not add release branches,
  wildcard branches, or tag rules.
- At least one required reviewer must be distinct from the workflow dispatcher.
- Self-review is prevented by enabling **Prevent self-review**, including when
  the dispatcher is a repository administrator.
- Administrator bypass is disabled by clearing **Allow administrators to bypass
  configured protection rules**.
- Release credentials exist only as environment secrets. Do not retain
  repository- or organization-level copies that bypass this environment.

The workflow job is bound to this environment. A run from any ref other than
`main`, a self-approved run, or a run that has not received independent approval
must not receive the release secrets.

## Required Secrets

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
comments are informational and are not executable references. The repository's
structural YAML check inspects decoded job-level and step-level `uses` nodes and
prohibits local action references until their manifests can be inspected
recursively.

The Central credentials must be a Central Portal user token.

`GATEWAY_CORE_RELEASE_GPG_FINGERPRINT` must be the full primary-key fingerprint.
Signatures made by one of that primary key's signing subkeys are accepted.

Set `GATEWAY_CORE_JAVA17_HOME` to a JDK 17 installation when the current
`JAVA_HOME` is not JDK 17. The GitHub workflow installs JDK 17, captures its
home, and then restores JDK 25 for the main release build.

### Provision The GPG Values Safely

Start from the full **primary** fingerprint previously verified against the
signer of a published release or another approved release record. Do not select
a key by email address, short key id, or first-match ordering, and do not
substitute a signing-subkey fingerprint:

```bash
FPR='<verified-full-primary-fingerprint>'
FPR="$(printf '%s' "$FPR" | tr '[:lower:]' '[:upper:]')"
case "$FPR" in
  ''|*[!0-9A-F]*) echo 'Invalid release fingerprint' >&2; exit 1 ;;
esac
if [ "${#FPR}" -ne 40 ] && [ "${#FPR}" -ne 64 ]; then
  echo 'Release fingerprint must contain 40 or 64 hexadecimal characters' >&2
  exit 1
fi

LOCAL_PRIMARY_FPR="$(
  gpg --batch --with-colons --fingerprint --list-secret-keys "$FPR" |
    awk -F: '$1 == "sec" { primary = 1; next }
             primary && $1 == "fpr" { print $10; exit }'
)"
test "$LOCAL_PRIMARY_FPR" = "$FPR"
printf '%s\n' "$FPR"
```

The fingerprint is safe to display and is the value for
`GATEWAY_CORE_RELEASE_GPG_FINGERPRINT`. Store it without putting it in shell
history:

```bash
printf '%s' "$FPR" |
  gh secret set GATEWAY_CORE_RELEASE_GPG_FINGERPRINT \
    --repo dtkmn/mcp-gateway-core \
    --env central-validation-upload
```

Export and Base64-encode the private key through a permission-restricted
temporary directory, then stream it directly into GitHub without terminal or
clipboard exposure:

```bash
(
  set -euo pipefail
  umask 077
  secret_dir="$(mktemp -d)"
  trap 'rm -rf "$secret_dir"' EXIT
  key_file="$secret_dir/release-private-key.asc"

  gpg --armor --export-secret-keys "$FPR" >"$key_file"
  test -s "$key_file"
  base64 <"$key_file" | tr -d '\n' |
    gh secret set GATEWAY_CORE_RELEASE_GPG_PRIVATE_KEY_B64 \
      --repo dtkmn/mcp-gateway-core \
      --env central-validation-upload
)
```

A private-key passphrase cannot be recovered from the key: retrieve it from
approved secure storage or rotate the signing key. Set it through the GitHub
CLI's interactive secret prompt rather than a command argument:

```bash
gh secret set GATEWAY_CORE_RELEASE_GPG_PASSPHRASE \
  --repo dtkmn/mcp-gateway-core \
  --env central-validation-upload
```

Never put the private key, its Base64 encoding, or its passphrase in command
arguments, shell history, clipboard managers, CI output, tickets, or chat.

## Prepare The Release Candidate

1. Start release preparation from the latest `main` and create a review branch.
2. Set `gatewayCoreVersion` to the intended, unpublished, non-snapshot version.
   Update release notes and all matching release metadata, but leave public
   dependency examples on the latest version actually available from Maven
   Central.
3. Run the development gate and required documentation checks, open a pull
   request, and merge only after required CI passes.
4. Confirm the candidate is now on `main` and record its exact commit SHA. This
   is the source commit that must be dry-run, uploaded, and eventually tagged.

The normal development version is a `-SNAPSHOT`; this script deliberately
rejects snapshots. A workflow may still be dispatched from another ref, but the
protected environment blocks its release job and secret access unless the ref
is `main`.

## Approval-Gated Dry Run

Dispatch the `Central Validation Upload` workflow from `main` with:

- `execute_upload`: `false`
- `upload_confirmation`: blank

The dispatcher must not approve their own deployment. The distinct required
reviewer must inspect the version and workflow source SHA, then approve the
environment deployment without using administrator bypass.

The dry run imports the release key, builds the public-preview proof, compiles and
runs both clean Java 17 downstream consumers against the release-version staged
artifacts, signs those artifacts, creates a closed-world Central bundle, and
verifies checksums and detached signatures from the extracted ZIP payload.

The dry run prints a confirmation token like:

```text
upload:io.github.dtkmn:mcp-gateway-public-preview:<version>:USER_MANAGED
```

Record the workflow's source SHA and exact confirmation token. The token encodes
the coordinates, version, and publishing type; it does **not** attest to a
source SHA or bundle digest and may be identical across different runs. No
Central API upload is made in this mode, and no publication is possible. If
`main` changes after this run, the dry-run evidence is stale: rerun from the new
head, verify that head independently, and submit the token printed by that run
even when its text is unchanged.

## Validation Upload

Confirm that the `main` head still matches the successful dry-run source SHA,
then dispatch the same workflow from `main` with:

- `execute_upload`: `true`
- `upload_confirmation`: the exact token printed by dry-run mode

The distinct required reviewer must approve this deployment again after
checking the source SHA, version, and confirmation token. If the source SHA
differs from the dry run, reject it and repeat the dry run.

The workflow uploads the verified bundle to:

```text
https://central.sonatype.com/api/v1/publisher/upload
```

with `publishingType=USER_MANAGED`.

Before uploading, the script checks both coordinates on Maven Central and fails
closed if either version is already published. Maven coordinates are immutable;
choose a new version instead of attempting to replace an existing artifact.

After upload, inspect the deployment in the Central Portal. If it reaches
`VALIDATED`, the artifact is available for manual publication later. Record the
deployment id and the validation-upload workflow's exact source SHA. The script
never calls the Central publish endpoint.

## Manual Publication

A validation upload is not a release. After the deployment reaches `VALIDATED`,
the project owner must make a separate, explicit publication decision. In the
Central Portal:

1. Open the recorded `USER_MANAGED` deployment.
2. Verify the deployment id, `io.github.dtkmn` namespace, version, and both
   components against the approved candidate.
3. Select the Portal's manual publish action only with explicit project-owner
   approval.
4. Wait for the deployment to reach its published state before changing public
   documentation or creating a release announcement.

Do not treat `execute_upload=true`, an upload response, a deployment id, or the
`VALIDATED` state as publication.

## Post-Publication Verification And Repository Finalization

After Central reports the deployment as published and repository propagation is
complete:

1. Verify both Maven coordinates at the released version:
   `io.github.dtkmn:mcp-gateway-core` and
   `io.github.dtkmn:mcp-gateway-spring-webflux`. Confirm each POM, binary JAR,
   sources JAR, Javadocs JAR, checksums, and detached signatures is publicly
   retrievable. Use a fresh external Java 17 Gradle or Maven project whose only
   artifact repository is Maven Central to confirm both coordinates resolve;
   the repository's staging-only consumer scripts do not prove Central
   propagation.
2. Tag the exact source commit recorded by the successful validation-upload
   workflow, not a later `main` head. Create and push an annotated `v<version>`
   tag only after both coordinates pass verification.
3. Finalize the release notes, update README/getting-started examples and other
   public documentation to the newly published version, and merge those changes.
4. Create the GitHub Release from the verified tag and use the finalized release
   notes. Check that the release links to the exact tagged source commit.
5. Synchronize `develop` with the finalized release state, then advance
   `gatewayCoreVersion` on `develop` to the next `-SNAPSHOT` version. Do not make
   that snapshot bump part of the published release tag.

The immutable tag prioritizes source provenance: it must identify the exact
commit used for the Central upload. Because public documentation cannot
truthfully switch to a version before publication, that tagged tree may retain
candidate-state wording. Do not move the tag to a later documentation commit;
finalize the live documentation on `main` and use those finalized notes for the
GitHub Release.
