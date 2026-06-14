#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="$(awk -F= '/^gatewayCoreVersion=/ { print $2; exit }' "${ROOT_DIR}/gradle.properties")"
STAGING_REPOSITORY="${GATEWAY_CORE_STAGING_REPOSITORY:-${ROOT_DIR}/build/staging-repository}"

fail() {
  echo "$*" >&2
  exit 1
}

if [[ -z "${VERSION}" ]]; then
  fail "gatewayCoreVersion is missing from gradle.properties."
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
  fail "java is not available; install/use Java 17 before running this smoke test."
fi

JAVA_MAJOR="$("${JAVA_BIN}" -version 2>&1 | awk -F '"' '/version/ { print $2; exit }' | awk -F. '{ print $1 }')"
if [[ "${JAVA_MAJOR}" != "17" ]]; then
  echo "This smoke test must run with Java 17. Current java version:" >&2
  "${JAVA_BIN}" -version >&2
  exit 1
fi

WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/mcp-gateway-java17-consumer.XXXXXX")"
cleanup() {
  rm -rf "${WORK_DIR}"
}
trap cleanup EXIT

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
        maven {
            url = uri(System.getenv("GATEWAY_CORE_STAGING_REPOSITORY"))
            content {
                includeGroup("io.github.dtkmn")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "mcp-gateway-java17-consumer"
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
    mainClass = 'Smoke'
}
GRADLE

mkdir -p "${WORK_DIR}/src/main/java"
cat > "${WORK_DIR}/src/main/java/Smoke.java" <<'JAVA'
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import mcp.gateway.core.authz.McpToolAccessRegistry;
import mcp.gateway.core.authz.McpToolAccessRule;
import mcp.gateway.core.authz.McpToolAuthorizer;
import mcp.gateway.core.authz.ToolAuthorizationDecision;
import mcp.gateway.core.context.GatewayToolExecutionContext;
import mcp.gateway.core.governance.GatewayToolAuthorizationPolicy;
import mcp.gateway.core.governance.GatewayToolGovernance;
import mcp.gateway.core.governance.GatewayToolGovernanceDecision;
import mcp.gateway.core.governance.GatewayToolGovernanceOutcome;
import mcp.gateway.core.invocation.McpToolInvocation;
import mcp.gateway.core.invocation.McpToolInvocationKind;
import mcp.gateway.core.protection.McpAbuseProtectionDecision;
import mcp.gateway.core.tool.McpToolSurface;
import mcp.gateway.spring.webflux.McpGatewayAuthorizationMode;
import mcp.gateway.spring.webflux.McpGatewayWebFluxGovernanceFilter;
import mcp.gateway.spring.webflux.McpJsonRpcToolInvocationParser;

public final class Smoke {
    public static void main(String[] args) {
        McpJsonRpcToolInvocationParser parser = new McpJsonRpcToolInvocationParser(new ObjectMapper());
        McpToolInvocation invocation = parser.parse(("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"demo_tool"}}
                """).getBytes(StandardCharsets.UTF_8));

        require(invocation.kind() == McpToolInvocationKind.TOOL_CALL, "expected tool call invocation");
        require("demo_tool".equals(invocation.toolName()), "expected parsed tool name");

        McpToolAccessRegistry registry = McpToolAccessRegistry.of(List.of(
                McpToolAccessRule.of("demo_tool", McpToolSurface.GUIDED, List.of("demo:execute"))
        ));
        McpToolAuthorizer authorizer = McpToolAuthorizer.of(registry, List.of("mcp:tools:list"));
        ToolAuthorizationDecision decision = authorizer.authorizeToolCall(
                "demo_tool",
                List.of("demo:execute"),
                false,
                true
        );

        require(decision.allowed(), "expected demo_tool authorization to be allowed");
        require(McpGatewayAuthorizationMode.ENFORCE.name().equals("ENFORCE"), "expected adapter enum to load");
        require(McpGatewayWebFluxGovernanceFilter.class.getName().contains("GovernanceFilter"),
                "expected combined governance filter to load");

        GatewayToolExecutionContext context = GatewayToolExecutionContext.of(
                "demo-client",
                "demo-workspace",
                "demo-correlation",
                invocation,
                null
        );
        GatewayToolGovernanceDecision governanceDecision = GatewayToolGovernance.evaluate(
                context,
                List.of("demo:execute"),
                new mcp.gateway.core.governance.GatewayToolAuthorizationEvaluator() {
                    @Override
                    public GatewayToolAuthorizationPolicy policy() {
                        return GatewayToolAuthorizationPolicy.enforce();
                    }

                    @Override
                    public ToolAuthorizationDecision authorize(
                            java.util.Collection<String> grantedScopes,
                            GatewayToolExecutionContext context
                    ) {
                        return authorizer.authorize(context, grantedScopes, false, true);
                    }
                },
                new mcp.gateway.core.governance.GatewayToolProtectionEvaluator() {
                    @Override
                    public boolean enabled() {
                        return true;
                    }

                    @Override
                    public McpAbuseProtectionDecision evaluate(GatewayToolExecutionContext context) {
                        return McpAbuseProtectionDecision.allow(
                                context.toolName(),
                                context.principalId(),
                                context.workspaceId()
                        );
                    }
                }
        );

        require(governanceDecision.outcome() == GatewayToolGovernanceOutcome.ALLOW,
                "expected governance decision to allow");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
JAVA

GATEWAY_CORE_STAGING_REPOSITORY="${STAGING_REPOSITORY}" \
  "${ROOT_DIR}/gradlew" -p "${WORK_DIR}" clean run --no-daemon --stacktrace

echo "Java 17 consumer smoke passed for mcp-gateway-core ${VERSION}."
