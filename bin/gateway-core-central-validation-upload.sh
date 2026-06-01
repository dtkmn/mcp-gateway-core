#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: ./bin/gateway-core-central-validation-upload.sh [options]

Guarded path for uploading the mcp-gateway-core bundle to Central for
USER_MANAGED validation. Default mode creates and verifies the release-signed
bundle but does not upload.

Options:
  --execute-upload        Upload the verified bundle to Central as USER_MANAGED.
  -h, --help              Show this help.

Required environment:
  GATEWAY_CORE_RELEASE_GPG_FINGERPRINT     Release signing key fingerprint.

Optional environment:
  GATEWAY_CORE_RELEASE_GPG_HOME            GPG home for release key lookup.
  GATEWAY_CORE_RELEASE_GPG_PASSPHRASE      Passphrase for release signing key.
  CENTRAL_API_BASE_URL                     Must be https://central.sonatype.com.

Required only with --execute-upload:
  CENTRAL_PORTAL_USERNAME                  Central Portal user token username.
  CENTRAL_PORTAL_PASSWORD                  Central Portal user token password.
  CENTRAL_UPLOAD_CONFIRMATION              Exact confirmation token printed by dry-run mode.

This script never calls the Central publish endpoint.
EOF
}

fail() {
  echo "mcp-gateway-core Central validation upload failed: $*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "missing required command: $1"
}

is_placeholder() {
  case "$1" in
    ""|"<"*">"|"..."|"changeme"|"CHANGE_ME"|"todo"|"TODO"|"example"|"EXAMPLE")
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

project_version() {
  sed -n "s/^gatewayCoreVersion=\(.*\)$/\1/p" gradle.properties | head -n 1
}

uppercase_fingerprint() {
  printf '%s' "$1" | tr -d '[:space:]' | tr '[:lower:]' '[:upper:]'
}

urlencode() {
  python3 - "$1" <<'PY'
import sys
import urllib.parse
print(urllib.parse.quote(sys.argv[1], safe=""))
PY
}

CENTRAL_API_BASE_URL_NORMALIZED=""
gpg_base_args=()
central_curl_config=""
central_portal_username=""
central_portal_password=""
central_upload_confirmation=""
gateway_core_release_gpg_fingerprint=""
gateway_core_release_gpg_home=""
gateway_core_release_gpg_passphrase=""

cleanup_sensitive_files() {
  if [ -n "${central_curl_config:-}" ]; then
    rm -f "$central_curl_config"
  fi
}
trap cleanup_sensitive_files EXIT

validate_central_api_base_url() {
  local raw="${CENTRAL_API_BASE_URL:-https://central.sonatype.com}"

  python3 - "$raw" <<'PY' >/dev/null \
    || fail "CENTRAL_API_BASE_URL must be https://central.sonatype.com for validation uploads"
import sys
import urllib.parse

raw = sys.argv[1].strip()
url = urllib.parse.urlparse(raw)
if (
    url.scheme != "https"
    or url.netloc != "central.sonatype.com"
    or url.path not in ("", "/")
    or url.params
    or url.query
    or url.fragment
):
    raise SystemExit(1)
print("https://central.sonatype.com")
PY
  CENTRAL_API_BASE_URL_NORMALIZED="https://central.sonatype.com"
}

capture_sensitive_environment() {
  central_portal_username="${CENTRAL_PORTAL_USERNAME:-}"
  central_portal_password="${CENTRAL_PORTAL_PASSWORD:-}"
  central_upload_confirmation="${CENTRAL_UPLOAD_CONFIRMATION:-}"
  gateway_core_release_gpg_fingerprint="${GATEWAY_CORE_RELEASE_GPG_FINGERPRINT:-}"
  gateway_core_release_gpg_home="${GATEWAY_CORE_RELEASE_GPG_HOME:-}"
  gateway_core_release_gpg_passphrase="${GATEWAY_CORE_RELEASE_GPG_PASSPHRASE:-}"

  unset CENTRAL_PORTAL_USERNAME
  unset CENTRAL_PORTAL_PASSWORD
  unset CENTRAL_UPLOAD_CONFIRMATION
  unset GATEWAY_CORE_RELEASE_GPG_FINGERPRINT
  unset GATEWAY_CORE_RELEASE_GPG_HOME
  unset GATEWAY_CORE_RELEASE_GPG_PASSPHRASE
}

verify_checksum() {
  local artifact="$1"
  local checksum_file="$2"
  local algorithm="$3"
  python3 - "$artifact" "$checksum_file" "$algorithm" <<'PY'
import hashlib
import pathlib
import sys

artifact = pathlib.Path(sys.argv[1])
checksum_file = pathlib.Path(sys.argv[2])
algorithm = sys.argv[3]
expected = hashlib.new(algorithm, artifact.read_bytes()).hexdigest().lower()
actual = checksum_file.read_text().strip().split()[0].lower()
if expected != actual:
    raise SystemExit(f"invalid {algorithm} checksum for {artifact.name}")
PY
}

configure_gpg() {
  local fingerprint="$gateway_core_release_gpg_fingerprint"
  if is_placeholder "$fingerprint"; then
    fail "GATEWAY_CORE_RELEASE_GPG_FINGERPRINT is required and must not be a placeholder"
  fi
  gateway_core_release_gpg_fingerprint="$(uppercase_fingerprint "$fingerprint")"

  gpg_base_args=(--batch)
  if [ -n "$gateway_core_release_gpg_home" ]; then
    [ -d "$gateway_core_release_gpg_home" ] \
      || fail "GATEWAY_CORE_RELEASE_GPG_HOME does not exist: $gateway_core_release_gpg_home"
    gpg_base_args+=(--homedir "$gateway_core_release_gpg_home")
  fi

  gpg "${gpg_base_args[@]}" --list-secret-keys --with-colons "$gateway_core_release_gpg_fingerprint" \
    | grep -q '^sec' \
    || fail "release GPG secret key was not found for GATEWAY_CORE_RELEASE_GPG_FINGERPRINT"
}

sign_artifact() {
  local artifact="$1"
  local signature="${artifact}.asc"
  local sign_args=("${gpg_base_args[@]}")
  rm -f "$signature"

  if [ -n "$gateway_core_release_gpg_passphrase" ]; then
    sign_args+=(--pinentry-mode loopback --passphrase-fd 0)
  fi
  sign_args+=(--local-user "$gateway_core_release_gpg_fingerprint" --armor --detach-sign --output "$signature" "$artifact")

  if [ -n "$gateway_core_release_gpg_passphrase" ]; then
    printf '%s' "$gateway_core_release_gpg_passphrase" | gpg "${sign_args[@]}" >/dev/null 2>&1 \
      || fail "release GPG key could not sign artifact: $(basename "$artifact")"
  else
    gpg "${sign_args[@]}" >/dev/null 2>&1 \
      || fail "release GPG key could not sign artifact: $(basename "$artifact")"
  fi
}

verify_signature() {
  local artifact="$1"
  local signature="${artifact}.asc"
  local status
  status="$(gpg "${gpg_base_args[@]}" --status-fd 1 --verify "$signature" "$artifact" 2>/dev/null)" \
    || fail "invalid detached signature for Central validation artifact: $(basename "$artifact")"
  grep -Fq "[GNUPG:] VALIDSIG ${gateway_core_release_gpg_fingerprint}" <<<"$status" \
    || fail "signature for $(basename "$artifact") was not made by GATEWAY_CORE_RELEASE_GPG_FINGERPRINT"
}

required_artifacts() {
  local version="$1"
  printf '%s\n' \
    "mcp-gateway-core-${version}.pom" \
    "mcp-gateway-core-${version}.jar" \
    "mcp-gateway-core-${version}-sources.jar" \
    "mcp-gateway-core-${version}-javadoc.jar"
}

copy_publication_for_release_signing() {
  local version="$1"
  local release_root="$2"
  local source_root="build/staging-repository"
  local source_artifact_dir="$source_root/io/github/dtkmn/mcp-gateway-core/$version"

  [ -d "$source_artifact_dir" ] \
    || fail "staged public-preview publication is missing for version $version"

  rm -rf "$release_root"
  mkdir -p "$release_root"
  cp -R "$source_root/." "$release_root/"
  find "$release_root/io/github/dtkmn/mcp-gateway-core" \
    -maxdepth 1 \
    -type f \
    -name 'maven-metadata.xml*' \
    -delete
}

sign_release_publication() {
  local version="$1"
  local artifact_dir="$2"
  local artifact

  while IFS= read -r artifact; do
    [ -f "$artifact_dir/$artifact" ] || fail "release publication is missing artifact: $artifact"
    sign_artifact "$artifact_dir/$artifact"
  done < <(required_artifacts "$version")
}

create_bundle() {
  local release_root="$1"
  local bundle="$2"
  local bundle_abs

  rm -f "$bundle"
  mkdir -p "$(dirname "$bundle")"
  bundle_abs="$(cd -P "$(dirname "$bundle")" && pwd)/$(basename "$bundle")"
  (
    cd "$release_root"
    zip -qr "$bundle_abs" io
  )
}

verify_release_bundle() {
  local version="$1"
  local bundle="$2"
  local verification_root="$3"
  local base="io/github/dtkmn/mcp-gateway-core/${version}"
  local expected_entries=24
  local checksums=(md5 sha1 sha256 sha512)
  local artifact
  local checksum

  [ -f "$bundle" ] || fail "release validation bundle does not exist: $bundle"

  rm -rf "$verification_root"
  mkdir -p "$verification_root"
  unzip -q "$bundle" -d "$verification_root"

  local non_directory_entries
  non_directory_entries="$(find "$verification_root" -type f | wc -l | tr -d ' ')"
  [ "$non_directory_entries" -eq "$expected_entries" ] \
    || fail "release validation bundle should contain exactly $expected_entries files, found $non_directory_entries"

  while IFS= read -r artifact; do
    local artifact_path="$verification_root/$base/$artifact"
    [ -f "$artifact_path" ] || fail "release validation bundle is missing artifact: $base/$artifact"
    [ -f "$artifact_path.asc" ] || fail "release validation bundle is missing signature: $base/$artifact.asc"
    verify_signature "$artifact_path"
    for checksum in "${checksums[@]}"; do
      [ -f "$artifact_path.$checksum" ] \
        || fail "release validation bundle is missing checksum: $base/$artifact.$checksum"
      verify_checksum "$artifact_path" "$artifact_path.$checksum" "$checksum"
    done
  done < <(required_artifacts "$version")

  find "$verification_root" -type f | while IFS= read -r file; do
    local relative="${file#$verification_root/}"
    local allowed=false
    while IFS= read -r artifact; do
      if [ "$relative" = "$base/$artifact" ] || [ "$relative" = "$base/$artifact.asc" ]; then
        allowed=true
      fi
      for checksum in "${checksums[@]}"; do
        if [ "$relative" = "$base/$artifact.$checksum" ]; then
          allowed=true
        fi
      done
    done < <(required_artifacts "$version")
    [ "$allowed" = "true" ] || fail "release validation bundle contains unexpected file: $relative"
  done
}

require_upload_env() {
  local expected_confirmation="$1"

  if is_placeholder "$central_portal_username"; then
    fail "CENTRAL_PORTAL_USERNAME is required for --execute-upload and must not be a placeholder"
  fi
  if is_placeholder "$central_portal_password"; then
    fail "CENTRAL_PORTAL_PASSWORD is required for --execute-upload and must not be a placeholder"
  fi
  [ "$central_upload_confirmation" = "$expected_confirmation" ] \
    || fail "CENTRAL_UPLOAD_CONFIRMATION must exactly equal: $expected_confirmation"
}

upload_user_managed_deployment() {
  local version="$1"
  local bundle="$2"
  local output_dir="$3"
  local api_base
  local curl_config
  local deployment_name
  local token
  local upload_url
  local deployment_id
  local deployment_response
  local status_response
  local status_error
  local status_attempt
  local status_delay

  validate_central_api_base_url
  api_base="$CENTRAL_API_BASE_URL_NORMALIZED"

  deployment_name="$(urlencode "io.github.dtkmn:mcp-gateway-core:${version}")"
  token="$(printf '%s:%s' "$central_portal_username" "$central_portal_password" | base64 | tr -d '\n')"
  upload_url="${api_base%/}/api/v1/publisher/upload?publishingType=USER_MANAGED&name=${deployment_name}"

  mkdir -p "$output_dir"
  curl_config="$(mktemp "${TMPDIR:-/tmp}/mgc-central-curl.XXXXXX")"
  central_curl_config="$curl_config"
  chmod 600 "$curl_config"
  printf 'header = "Authorization: Bearer %s"\n' "$token" > "$curl_config"
  deployment_response="$output_dir/deployment-upload-response.txt"
  curl --silent --show-error --fail-with-body \
    --request POST \
    --config "$curl_config" \
    --form "bundle=@${bundle};type=application/octet-stream" \
    --output "$deployment_response" \
    "$upload_url" \
    || fail "Central upload request failed; response saved to $deployment_response"

  tr -d '\r\n' < "$deployment_response" > "$output_dir/deployment-id.txt"
  grep -Eq '^[0-9a-fA-F-]{32,36}$' "$output_dir/deployment-id.txt" \
    || fail "Central upload did not return a deployment id"
  deployment_id="$(cat "$output_dir/deployment-id.txt")"

  status_response="$output_dir/deployment-status.json"
  status_error="$output_dir/deployment-status-error.txt"
  status_delay=5
  for status_attempt in 1 2 3 4 5; do
    if curl --silent --show-error --fail-with-body \
      --request POST \
      --config "$curl_config" \
      --output "$status_response" \
      "${api_base%/}/api/v1/publisher/status?id=${deployment_id}" \
      2>"$status_error"; then
      rm -f "$curl_config"
      central_curl_config=""
      rm -f "$status_error"
      return 0
    fi
    if [ "$status_attempt" -lt 5 ]; then
      sleep "$status_delay"
      status_delay=$((status_delay * 2))
    fi
  done

  cat > "$status_response" <<EOF
{"deploymentId":"${deployment_id}","deploymentState":"STATUS_UNAVAILABLE","message":"Central accepted the upload, but the status endpoint was not available during the immediate post-upload check. Review the USER_MANAGED deployment in the Central Portal."}
EOF
  rm -f "$curl_config"
  central_curl_config=""
  echo "Central upload returned deployment id ${deployment_id}, but immediate status lookup was unavailable after retries." >&2
  echo "Status lookup stderr is saved to $status_error." >&2
}

execute_upload=false

while [ "$#" -gt 0 ]; do
  case "$1" in
    --execute-upload)
      execute_upload=true
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      fail "unknown argument: $1"
      ;;
  esac
  shift
done

require_command base64
require_command curl
require_command gpg
require_command python3
require_command unzip
require_command zip

capture_sensitive_environment

publishing_type="${CENTRAL_PUBLISHING_TYPE:-USER_MANAGED}"
[ "$publishing_type" = "USER_MANAGED" ] \
  || fail "CENTRAL_PUBLISHING_TYPE must stay USER_MANAGED for validation upload"

version="$(project_version)"
[ -n "$version" ] || fail "could not resolve gatewayCoreVersion from gradle.properties"
case "$version" in
  *-SNAPSHOT)
    fail "Central validation upload requires a release version, got: $version"
    ;;
esac

configure_gpg

./gradlew verifyGatewayCorePublicPreviewPublication --no-daemon --stacktrace

work_root="build/gateway-core-central-validation-upload"
release_root="$work_root/publication"
artifact_dir="$release_root/io/github/dtkmn/mcp-gateway-core/$version"
bundle="$work_root/mcp-gateway-core-${version}-central-validation-bundle.zip"
verification_root="$work_root/verification"
confirmation_token="upload:io.github.dtkmn:mcp-gateway-core:${version}:USER_MANAGED"

copy_publication_for_release_signing "$version" "$release_root"
sign_release_publication "$version" "$artifact_dir"
create_bundle "$release_root" "$bundle"
verify_release_bundle "$version" "$bundle" "$verification_root"

if [ "$execute_upload" = "true" ]; then
  require_upload_env "$confirmation_token"
  upload_user_managed_deployment "$version" "$bundle" "$work_root"
  deployment_id="$(cat "$work_root/deployment-id.txt")"
  cat <<EOF
mcp-gateway-core Central validation upload submitted.
- Coordinate: io.github.dtkmn:mcp-gateway-core:${version}
- Publishing type: USER_MANAGED
- Deployment id: ${deployment_id}
- Status artifact: ${work_root}/deployment-status.json
- Upload response artifact: ${work_root}/deployment-upload-response.txt
- Publish action: not called
EOF
else
  cat <<EOF
mcp-gateway-core Central validation upload dry run passed.
- Coordinate: io.github.dtkmn:mcp-gateway-core:${version}
- Publishing type: USER_MANAGED
- Release-signed bundle: ${bundle}
- Upload action: disabled
- To upload for Central validation, rerun with:
  CENTRAL_UPLOAD_CONFIRMATION=${confirmation_token} ./bin/gateway-core-central-validation-upload.sh --execute-upload
EOF
fi
