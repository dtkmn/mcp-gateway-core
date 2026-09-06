#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="$(awk -F= '/^gatewayCoreVersion=/ { print $2; exit }' "${ROOT_DIR}/gradle.properties")"
SPRING_FRAMEWORK_VERSION="$(awk -F= '/^springFrameworkVersion=/ { print $2; exit }' "${ROOT_DIR}/gradle.properties")"
SPRING_FRAMEWORK_VERSION="${SPRING_FRAMEWORK_VERSION:-7.0.8}"
STAGING_REPOSITORY="${GATEWAY_CORE_STAGING_REPOSITORY:-${ROOT_DIR}/build/staging-repository}"

fail() {
  echo "$*" >&2
  exit 1
}

if [[ -z "${VERSION}" ]]; then
  fail "gatewayCoreVersion is missing from gradle.properties."
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

rootProject.name = "mcp-gateway-java17-consumer"
include "core-consumer", "webflux-consumer"
GRADLE

cat > "${WORK_DIR}/build.gradle" <<GRADLE
subprojects {
    apply plugin: 'application'

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(17)
        }
    }
}

project(':core-consumer') {
    dependencies {
        implementation "io.github.dtkmn:mcp-gateway-core:${VERSION}"
    }

    application {
        mainClass = 'CoreSmoke'
    }
}

project(':webflux-consumer') {
    dependencies {
        implementation "io.github.dtkmn:mcp-gateway-spring-webflux:${VERSION}"
        implementation "org.springframework:spring-test:${SPRING_FRAMEWORK_VERSION}"
    }

    application {
        mainClass = 'WebFluxSmoke'
    }
}

GRADLE

# Exercise the published API on Java 17; detailed behavior stays in the unit tests.
mkdir -p "${WORK_DIR}/core-consumer/src/main/java"
cat > "${WORK_DIR}/core-consumer/src/main/java/CoreSmoke.java" <<'JAVA'
import java.util.List;
import mcp.gateway.core.authz.McpToolAccessRegistry;
import mcp.gateway.core.authz.McpToolAccessRule;
import mcp.gateway.core.authz.McpToolAuthorizer;
import mcp.gateway.core.tool.McpToolSurface;

public final class CoreSmoke {
    public static void main(String[] args) {
        McpToolAuthorizer authorizer = McpToolAuthorizer.of(McpToolAccessRegistry.of(List.of(
                McpToolAccessRule.of("demo_tool", McpToolSurface.GUIDED, List.of("demo:run"))
        )), List.of("mcp:tools:list"));

        if (!authorizer.authorizeToolCall("demo_tool", List.of("demo:run"), false, true).allowed()) {
            throw new IllegalStateException("Authorized core consumer call was denied");
        }
        if (authorizer.authorizeToolCall("demo_tool", List.of(), false, true).allowed()) {
            throw new IllegalStateException("Core consumer call without scope was allowed");
        }
    }
}
JAVA

mkdir -p "${WORK_DIR}/webflux-consumer/src/main/java"
cat > "${WORK_DIR}/webflux-consumer/src/main/java/WebFluxSmoke.java" <<'JAVA'
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import mcp.gateway.core.authz.McpToolAccessRegistry;
import mcp.gateway.core.authz.McpToolAccessRule;
import mcp.gateway.core.authz.McpToolAuthorizer;
import mcp.gateway.core.context.GatewayToolExecutionContext;
import mcp.gateway.core.tool.McpToolSurface;
import mcp.gateway.spring.webflux.McpGatewayAuthorizationMode;
import mcp.gateway.spring.webflux.McpGatewayWebFluxGovernanceFilter;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

public final class WebFluxSmoke {
    private static final String BODY = """
            {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"demo_tool"}}
            """;

    public static void main(String[] args) {
        McpToolAuthorizer authorizer = McpToolAuthorizer.of(McpToolAccessRegistry.of(List.of(
                McpToolAccessRule.of("demo_tool", McpToolSurface.GUIDED, List.of("demo:run"))
        )), List.of("mcp:tools:list"));
        McpGatewayWebFluxGovernanceFilter filter = McpGatewayWebFluxGovernanceFilter
                .builder(JsonMapper.builder().build(), (authentication, exchange, invocation) ->
                        GatewayToolExecutionContext.of(
                                authentication.getName(), "demo-workspace", null, invocation, null
                        ))
                .authorization(() -> McpGatewayAuthorizationMode.ENFORCE,
                        (scopes, context) -> authorizer.authorize(context, scopes, false, true))
                .build();

        AtomicReference<String> downstreamBody = new AtomicReference<>();
        filter.filter(exchange("demo:run"), captureBodyChain(downstreamBody)).block();
        require(BODY.equals(downstreamBody.get()), "Authorized request must reach downstream with its body");

        ServerWebExchange denied = exchange("demo:read");
        filter.filter(denied, ignored -> Mono.error(new IllegalStateException("Denied request reached downstream")))
                .block();
        require(HttpStatus.FORBIDDEN.equals(denied.getResponse().getStatusCode()),
                "Request without the required scope must return 403");
        String response = ((MockServerHttpResponse) denied.getResponse()).getBodyAsString().block();
        require(response != null && response.contains("insufficient_scope"),
                "Denied request must produce an authorization error response");
    }

    private static ServerWebExchange exchange(String scope) {
        return MockServerWebExchange.from(MockServerHttpRequest.post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(BODY))
                .mutate()
                .principal(Mono.just(new UsernamePasswordAuthenticationToken(
                        "demo-client", "n/a", List.of(new SimpleGrantedAuthority("SCOPE_" + scope))
                )))
                .build();
    }

    private static WebFilterChain captureBodyChain(AtomicReference<String> downstreamBody) {
        return exchange -> DataBufferUtils.join(exchange.getRequest().getBody())
                .doOnNext(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    DataBufferUtils.release(buffer);
                    downstreamBody.set(new String(bytes, StandardCharsets.UTF_8));
                })
                .then();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
JAVA

GATEWAY_CORE_STAGING_REPOSITORY="${STAGING_REPOSITORY}" \
  "${ROOT_DIR}/gradlew" -p "${WORK_DIR}" :core-consumer:run :webflux-consumer:run \
  --no-daemon --stacktrace

echo "Java 17 consumer smoke passed for core-only and WebFlux consumers using mcp-gateway artifacts ${VERSION}."
