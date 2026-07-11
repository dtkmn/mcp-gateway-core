#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

usage() {
  cat <<'EOF'
Usage: ./bin/gateway-public-preview-central-validation-upload.sh [options]

Guarded path for uploading the MCP Gateway public-preview bundle to Central for
USER_MANAGED validation. The bundle contains mcp-gateway-core and
mcp-gateway-spring-webflux. Default mode creates and verifies the release-signed
bundle but does not upload.

Options:
  --execute-upload        Upload the verified bundle to Central as USER_MANAGED.
  -h, --help              Show this help.

Required environment:
  GATEWAY_CORE_RELEASE_GPG_FINGERPRINT     Release signing key fingerprint.

Optional environment:
  GATEWAY_CORE_RELEASE_GPG_HOME            GPG home for release key lookup.
  GATEWAY_CORE_RELEASE_GPG_PASSPHRASE      Passphrase for release signing key.
  GATEWAY_CORE_JAVA17_HOME                 JDK 17 used for release consumer checks.
                                           Defaults to JAVA_HOME/current Java when it is JDK 17.
  CENTRAL_API_BASE_URL                     Must be https://central.sonatype.com.

Required only with --execute-upload:
  CENTRAL_PORTAL_USERNAME                  Central Portal user token username.
  CENTRAL_PORTAL_PASSWORD                  Central Portal user token password.
  CENTRAL_UPLOAD_CONFIRMATION              Exact confirmation token printed by dry-run mode.

This script never calls the Central publish endpoint.
EOF
}

fail() {
  echo "MCP Gateway public-preview Central validation upload failed: $*" >&2
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

java_major_version() {
  "$1" -version 2>&1 | awk -F '"' '/version/ { print $2; exit }' | awk -F. '{ print $1 }'
}

resolve_java17_home() {
  local requested_home="${GATEWAY_CORE_JAVA17_HOME:-}"
  local candidate_home=""
  local java_bin=""

  if [ -n "$requested_home" ]; then
    candidate_home="$requested_home"
    java_bin="$candidate_home/bin/java"
  elif [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ] \
      && [ "$(java_major_version "${JAVA_HOME}/bin/java")" = "17" ]; then
    candidate_home="$JAVA_HOME"
    java_bin="$candidate_home/bin/java"
  else
    java_bin="$(command -v java || true)"
    if [ -n "$java_bin" ] && [ "$(java_major_version "$java_bin")" = "17" ]; then
      candidate_home="$(cd "$(dirname "$java_bin")/.." && pwd -P)"
      java_bin="$candidate_home/bin/java"
    fi
  fi

  if [ -z "$candidate_home" ] || [ ! -x "$java_bin" ] \
      || [ ! -x "$candidate_home/bin/javac" ] \
      || [ "$(java_major_version "$java_bin")" != "17" ]; then
    fail "GATEWAY_CORE_JAVA17_HOME must point to a JDK 17 installation (or the current JAVA_HOME/java must be JDK 17)"
  fi

  printf '%s\n' "$candidate_home"
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
  local key_listing
  local primary_fingerprint
  if is_placeholder "$fingerprint"; then
    fail "GATEWAY_CORE_RELEASE_GPG_FINGERPRINT is required and must not be a placeholder"
  fi
  gateway_core_release_gpg_fingerprint="$(uppercase_fingerprint "$fingerprint")"
  if [[ ! "$gateway_core_release_gpg_fingerprint" =~ ^([0-9A-F]{40}|[0-9A-F]{64})$ ]]; then
    fail "GATEWAY_CORE_RELEASE_GPG_FINGERPRINT must be a full 40- or 64-character hexadecimal fingerprint"
  fi

  gpg_base_args=(--batch)
  if [ -n "$gateway_core_release_gpg_home" ]; then
    [ -d "$gateway_core_release_gpg_home" ] \
      || fail "GATEWAY_CORE_RELEASE_GPG_HOME does not exist: $gateway_core_release_gpg_home"
    gpg_base_args+=(--homedir "$gateway_core_release_gpg_home")
  fi

  key_listing="$(
    gpg "${gpg_base_args[@]}" --with-colons --fingerprint --list-secret-keys \
      "$gateway_core_release_gpg_fingerprint" 2>/dev/null
  )" || fail "release GPG secret key was not found for GATEWAY_CORE_RELEASE_GPG_FINGERPRINT"
  primary_fingerprint="$(awk -F: '$1 == "sec" { in_primary = 1; next } in_primary && $1 == "fpr" { print $10; exit }' <<<"$key_listing")"
  [ "$primary_fingerprint" = "$gateway_core_release_gpg_fingerprint" ] \
    || fail "GATEWAY_CORE_RELEASE_GPG_FINGERPRINT must identify the full primary-key fingerprint"
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
  awk -v expected="$gateway_core_release_gpg_fingerprint" \
    '$1 == "[GNUPG:]" && $2 == "VALIDSIG" && ($3 == expected || $NF == expected) { valid = 1 }
     END { exit valid ? 0 : 1 }' <<<"$status" \
    || fail "signature for $(basename "$artifact") was not made by GATEWAY_CORE_RELEASE_GPG_FINGERPRINT"
}

public_artifact_ids() {
  printf '%s\n' \
    "mcp-gateway-core" \
    "mcp-gateway-spring-webflux"
}

required_artifacts() {
  local artifact_id="$1"
  local version="$2"
  printf '%s\n' \
    "${artifact_id}-${version}.pom" \
    "${artifact_id}-${version}.jar" \
    "${artifact_id}-${version}-sources.jar" \
    "${artifact_id}-${version}-javadoc.jar"
}

copy_publication_for_release_signing() {
  local version="$1"
  local release_root="$2"
  local source_root="build/staging-repository"
  local artifact_id
  local artifact
  local checksum

  rm -rf "$release_root"

  while IFS= read -r artifact_id; do
    local source_artifact_dir="$source_root/io/github/dtkmn/$artifact_id/$version"
    local target_artifact_dir="$release_root/io/github/dtkmn/$artifact_id/$version"

    [ -d "$source_artifact_dir" ] \
      || fail "staged public-preview publication is missing for ${artifact_id}:${version}"

    mkdir -p "$target_artifact_dir"

    while IFS= read -r artifact; do
      [ -f "$source_artifact_dir/$artifact" ] \
        || fail "staged public-preview publication is missing artifact: $artifact"
      cp "$source_artifact_dir/$artifact" "$target_artifact_dir/$artifact"

      for checksum in md5 sha1 sha256 sha512; do
        [ -f "$source_artifact_dir/$artifact.$checksum" ] \
          || fail "staged public-preview publication is missing checksum: $artifact.$checksum"
        cp "$source_artifact_dir/$artifact.$checksum" "$target_artifact_dir/$artifact.$checksum"
      done
    done < <(required_artifacts "$artifact_id" "$version")
  done < <(public_artifact_ids)
}

sign_release_publication() {
  local version="$1"
  local release_root="$2"
  local artifact_id
  local artifact

  while IFS= read -r artifact_id; do
    local artifact_dir="$release_root/io/github/dtkmn/$artifact_id/$version"
    while IFS= read -r artifact; do
      [ -f "$artifact_dir/$artifact" ] || fail "release publication is missing artifact: $artifact"
      sign_artifact "$artifact_dir/$artifact"
    done < <(required_artifacts "$artifact_id" "$version")
  done < <(public_artifact_ids)
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
  local expected_entries=48
  local checksums=(md5 sha1 sha256 sha512)
  local artifact_id
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

  while IFS= read -r artifact_id; do
    local base="io/github/dtkmn/${artifact_id}/${version}"
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
    done < <(required_artifacts "$artifact_id" "$version")
  done < <(public_artifact_ids)

  find "$verification_root" -type f | while IFS= read -r file; do
    local relative="${file#$verification_root/}"
    local allowed=false
    while IFS= read -r artifact_id; do
      local base="io/github/dtkmn/${artifact_id}/${version}"
      while IFS= read -r artifact; do
        if [ "$relative" = "$base/$artifact" ] || [ "$relative" = "$base/$artifact.asc" ]; then
          allowed=true
        fi
        for checksum in "${checksums[@]}"; do
          if [ "$relative" = "$base/$artifact.$checksum" ]; then
            allowed=true
          fi
        done
      done < <(required_artifacts "$artifact_id" "$version")
    done < <(public_artifact_ids)
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

ensure_version_is_unpublished() {
  local version="$1"
  local artifact_id
  local pom_url
  local status

  while IFS= read -r artifact_id; do
    pom_url="https://repo1.maven.org/maven2/io/github/dtkmn/${artifact_id}/${version}/${artifact_id}-${version}.pom"
    status="$(
      curl --silent --show-error --location --head \
        --output /dev/null \
        --write-out '%{http_code}' \
        "$pom_url"
    )" || fail "could not verify whether ${artifact_id}:${version} is already published"

    case "$status" in
      200)
        fail "refusing to reuse immutable Maven Central coordinate io.github.dtkmn:${artifact_id}:${version}"
        ;;
      404)
        ;;
      *)
        fail "Maven Central returned HTTP ${status} while checking io.github.dtkmn:${artifact_id}:${version}"
        ;;
    esac
  done < <(public_artifact_ids)
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

  deployment_name="$(urlencode "io.github.dtkmn:mcp-gateway-public-preview:${version}")"
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

main() {
  local execute_upload=false
  local java17_home

  cd "$ROOT_DIR"
  trap cleanup_sensitive_files EXIT

while [ "$#" -gt 0 ]; do
  case "$1" in
    --execute-upload)
      execute_upload=true
      ;;
    -h|--help)
      usage
      return 0
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
if [[ ! "$version" =~ ^[0-9]+([.][0-9]+){2}([.-][0-9A-Za-z]+)*$ ]]; then
  fail "gatewayCoreVersion must be a Maven-safe semantic version, got: $version"
fi
case "$version" in
  *-SNAPSHOT)
    fail "Central validation upload requires a release version, got: $version"
    ;;
esac

if [ "$execute_upload" = "true" ]; then
  ensure_version_is_unpublished "$version"
fi

java17_home="$(resolve_java17_home)"

configure_gpg

./gradlew verifyGatewayPublicPreviewPublication \
  -PgatewayCoreGroup="io.github.dtkmn" \
  -PgatewayCoreVersion="$version" \
  -PgatewayCorePublicationRepositoryUrl="file://$(pwd)/build/staging-repository" \
  --no-daemon --stacktrace --warning-mode fail

GATEWAY_CORE_STAGING_REPOSITORY="$ROOT_DIR/build/staging-repository" \
  JAVA_HOME="$java17_home" \
  "$ROOT_DIR/bin/java17-consumer-smoke.sh"
GATEWAY_CORE_STAGING_REPOSITORY="$ROOT_DIR/build/staging-repository" \
  JAVA_HOME="$java17_home" \
  "$ROOT_DIR/bin/java17-source-compat-0.6-consumer.sh"

work_root="build/gateway-public-preview-central-validation-upload"
release_root="$work_root/publication"
bundle="$work_root/mcp-gateway-public-preview-${version}-central-validation-bundle.zip"
verification_root="$work_root/verification"
confirmation_token="upload:io.github.dtkmn:mcp-gateway-public-preview:${version}:USER_MANAGED"

copy_publication_for_release_signing "$version" "$release_root"
sign_release_publication "$version" "$release_root"
create_bundle "$release_root" "$bundle"
verify_release_bundle "$version" "$bundle" "$verification_root"

if [ "$execute_upload" = "true" ]; then
  require_upload_env "$confirmation_token"
  ensure_version_is_unpublished "$version"
  upload_user_managed_deployment "$version" "$bundle" "$work_root"
  deployment_id="$(cat "$work_root/deployment-id.txt")"
  cat <<EOF
MCP Gateway public-preview Central validation upload submitted.
- Components:
  - io.github.dtkmn:mcp-gateway-core:${version}
  - io.github.dtkmn:mcp-gateway-spring-webflux:${version}
- Publishing type: USER_MANAGED
- Deployment id: ${deployment_id}
- Status artifact: ${work_root}/deployment-status.json
- Upload response artifact: ${work_root}/deployment-upload-response.txt
- Publish action: not called
EOF
else
  cat <<EOF
MCP Gateway public-preview Central validation upload dry run passed.
- Components:
  - io.github.dtkmn:mcp-gateway-core:${version}
  - io.github.dtkmn:mcp-gateway-spring-webflux:${version}
- Publishing type: USER_MANAGED
- Release-signed bundle: ${bundle}
- Upload action: disabled
- To upload for Central validation, rerun with:
  CENTRAL_UPLOAD_CONFIRMATION=${confirmation_token} ./bin/gateway-public-preview-central-validation-upload.sh --execute-upload
EOF
fi
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi
