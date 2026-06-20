#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="$(awk -F= '/^gatewayCoreVersion=/ { print $2; exit }' "${ROOT_DIR}/gradle.properties")"
STAGING_REPOSITORY="${GATEWAY_CORE_STAGING_REPOSITORY:-${ROOT_DIR}/build/staging-repository}"
FIXTURE_SOURCE="${ROOT_DIR}/compatibility/source-compat/0.6.0/src/main/java/SourceCompat060.java"

fail() {
  echo "$*" >&2
  exit 1
}

if [[ -z "${VERSION}" ]]; then
  fail "gatewayCoreVersion is missing from gradle.properties."
fi

if [[ ! -f "${FIXTURE_SOURCE}" ]]; then
  fail "Missing frozen 0.6.0 source compatibility fixture: ${FIXTURE_SOURCE}"
fi

if [[ ! -d "${STAGING_REPOSITORY}/io/github/dtkmn/mcp-gateway-core/${VERSION}" ]]; then
  fail "Missing staged mcp-gateway-core ${VERSION} under ${STAGING_REPOSITORY}. Run ./gradlew verifyGatewayPublicPreviewPublication first."
fi

if [[ ! -d "${STAGING_REPOSITORY}/io/github/dtkmn/mcp-gateway-spring-webflux/${VERSION}" ]]; then
  fail "Missing staged mcp-gateway-spring-webflux ${VERSION} under ${STAGING_REPOSITORY}. Run ./gradlew verifyGatewayPublicPreviewPublication first."
fi

if [[ -n "${JAVA_HOME:-}" ]]; then
  JAVA_BIN="${JAVA_HOME}/bin/java"
else
  JAVA_BIN="$(command -v java || true)"
fi

if [[ -z "${JAVA_BIN}" || ! -x "${JAVA_BIN}" ]]; then
  fail "java is not available; install/use Java 17 before running this source-compat test."
fi

JAVA_MAJOR="$("${JAVA_BIN}" -version 2>&1 | awk -F '"' '/version/ { print $2; exit }' | awk -F. '{ print $1 }')"
if [[ "${JAVA_MAJOR}" != "17" ]]; then
  echo "This source-compat test must run with Java 17. Current java version:" >&2
  "${JAVA_BIN}" -version >&2
  exit 1
fi

WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/mcp-gateway-source-compat-060.XXXXXX")"
cleanup() {
  rm -rf "${WORK_DIR}"
}
trap cleanup EXIT

if [[ "${WORK_DIR}" == "${ROOT_DIR}" || "${WORK_DIR}" == "${ROOT_DIR}/"* ]]; then
  fail "Source-compat fixture must run outside the repository checkout."
fi

cat > "${WORK_DIR}/settings.gradle" <<'GRADLE'
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository {
                maven {
                    url = uri(System.getenv("GATEWAY_CORE_STAGING_REPOSITORY"))
                }
            }
            filter {
                includeGroup("io.github.dtkmn")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "mcp-gateway-source-compat-060"
GRADLE

cat > "${WORK_DIR}/build.gradle" <<GRADLE
plugins {
    id 'application'
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

dependencies {
    implementation "io.github.dtkmn:mcp-gateway-core:${VERSION}"
    implementation "io.github.dtkmn:mcp-gateway-spring-webflux:${VERSION}"
}

application {
    mainClass = 'SourceCompat060'
}

tasks.register('verifyGatewayFixtureResolution') {
    group = 'verification'
    description = 'Verifies gateway artifacts resolve only from the staged repository.'

    doLast {
        File stagingRoot = new File(System.getenv('GATEWAY_CORE_STAGING_REPOSITORY')).canonicalFile
        def gatewayArtifacts = configurations.runtimeClasspath.resolvedConfiguration.resolvedArtifacts.findAll {
            it.moduleVersion.id.group == 'io.github.dtkmn'
        }
        def expected = ['mcp-gateway-core', 'mcp-gateway-spring-webflux'] as Set
        def actual = gatewayArtifacts.collect { it.name } as Set
        if (actual != expected) {
            throw new GradleException("Expected staged gateway artifacts \${expected}, got \${actual}.")
        }
        gatewayArtifacts.each { artifact ->
            File artifactFile = artifact.file.canonicalFile
            if (!artifactFile.path.startsWith(stagingRoot.path + File.separator)) {
                throw new GradleException("Gateway artifact resolved outside staging repository: \${artifactFile}")
            }
        }
    }
}
GRADLE

mkdir -p "${WORK_DIR}/src/main/java"
cp "${FIXTURE_SOURCE}" "${WORK_DIR}/src/main/java/SourceCompat060.java"

if grep -R "mavenLocal" "${WORK_DIR}" >/dev/null 2>&1; then
  fail "Source-compat temp project must not use mavenLocal()."
fi

GATEWAY_CORE_STAGING_REPOSITORY="${STAGING_REPOSITORY}" \
  "${ROOT_DIR}/gradlew" -p "${WORK_DIR}" clean compileJava verifyGatewayFixtureResolution --no-daemon --stacktrace

echo "Java 17 frozen 0.6.0 source compatibility passed for mcp-gateway artifacts ${VERSION}."
