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

require_staged_artifact() {
  local artifact_id="$1"
  local artifact_dir="${STAGING_REPOSITORY}/io/github/dtkmn/${artifact_id}/${VERSION}"
  if [[ "${VERSION}" == *-SNAPSHOT ]]; then
    [[ -f "${artifact_dir}/maven-metadata.xml" ]] \
      || fail "Missing staged ${artifact_id} ${VERSION} snapshot metadata under ${artifact_dir}. Run ./gradlew verifyGatewayDevelopment first."
    return
  fi
  [[ -f "${artifact_dir}/${artifact_id}-${VERSION}.pom" ]] \
    || fail "Missing staged ${artifact_id} ${VERSION} POM under ${artifact_dir}. Run ./gradlew verifyGatewayDevelopment for normal development or the release gate during release preparation."
  [[ -f "${artifact_dir}/${artifact_id}-${VERSION}.jar" ]] \
    || fail "Missing staged ${artifact_id} ${VERSION} JAR under ${artifact_dir}. Run ./gradlew verifyGatewayDevelopment for normal development or the release gate during release preparation."
}

require_staged_artifact "mcp-gateway-core"
require_staged_artifact "mcp-gateway-spring-webflux"

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
        mavenCentral {
            content {
                excludeGroup("io.github.dtkmn")
            }
        }
    }
}

rootProject.name = "mcp-gateway-java17-consumer"
include "core-consumer", "webflux-consumer"
GRADLE

cat > "${WORK_DIR}/build.gradle" <<GRADLE
plugins {
    id 'base'
}

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

tasks.register('verifyGatewaySmokeResolution') {
    group = 'verification'
    description = 'Verifies smoke consumers resolve gateway artifacts only from the staged repository.'

    doLast {
        File stagingRoot = new File(System.getenv('GATEWAY_CORE_STAGING_REPOSITORY')).canonicalFile
        Map<String, Set<String>> expectedByProject = [
                'core-consumer': ['mcp-gateway-core'] as Set,
                'webflux-consumer': ['mcp-gateway-core', 'mcp-gateway-spring-webflux'] as Set
        ]
        subprojects.each { consumerProject ->
            def gatewayArtifacts = consumerProject.configurations.runtimeClasspath.resolvedConfiguration.resolvedArtifacts.findAll {
                it.moduleVersion.id.group == 'io.github.dtkmn'
            }
            Set<String> expected = expectedByProject[consumerProject.name]
            Set<String> actual = gatewayArtifacts.collect { it.name } as Set
            if (actual != expected) {
                throw new GradleException("Expected staged gateway artifacts \${expected} for \${consumerProject.name}, got \${actual}.")
            }
            gatewayArtifacts.each { artifact ->
                File artifactFile = artifact.file.canonicalFile
                if (!artifactFile.path.startsWith(stagingRoot.path + File.separator)) {
                    throw new GradleException("Gateway artifact resolved outside staging repository: \${artifactFile}")
                }
            }
        }
    }
}
GRADLE

mkdir -p "${WORK_DIR}/core-consumer/src/main/java"
cat > "${WORK_DIR}/core-consumer/src/main/java/CoreSmoke.java" <<'JAVA'
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

public final class CoreSmoke {
    public static void main(String[] args) {
        McpToolInvocation invocation = McpToolInvocation.fromJsonRpc(
                McpToolInvocation.METHOD_TOOLS_CALL,
                " demo_tool "
        );

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
        require(decision.mapped(), "expected demo_tool authorization to be mapped");

        GatewayToolExecutionContext toolsListContext = GatewayToolExecutionContext.of(
                "demo-client",
                "demo-workspace",
                "demo-correlation",
                McpToolInvocation.fromJsonRpc(McpToolInvocation.METHOD_TOOLS_LIST, null),
                null
        );
        ToolAuthorizationDecision toolsListDecision = authorizer.authorize(
                toolsListContext,
                List.of("mcp:tools:list"),
                false,
                true
        );
        require(toolsListDecision.allowed(), "expected tools/list authorization to be allowed");
        require(McpToolAuthorizer.TOOLS_LIST_ACTION.equals(toolsListDecision.actionName()),
                "expected tools/list synthetic gateway action");

        ToolAuthorizationDecision unmappedDecision = authorizer.authorizeToolCall(
                "missing_tool",
                List.of("*"),
                true,
                true
        );
        require(!unmappedDecision.allowed(), "expected unmapped tool authorization to fail closed");
        require(!unmappedDecision.mapped(), "expected unmapped tool authorization to be marked unmapped");

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

mkdir -p "${WORK_DIR}/webflux-consumer/src/main/java"
cat > "${WORK_DIR}/webflux-consumer/src/main/java/WebFluxSmoke.java" <<'JAVA'
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import mcp.gateway.core.authz.ToolAuthorizationDecision;
import mcp.gateway.core.context.GatewayToolExecutionContext;
import mcp.gateway.core.invocation.McpToolInvocation;
import mcp.gateway.core.invocation.McpToolInvocationKind;
import mcp.gateway.core.protection.McpAbuseProtectionDecision;
import mcp.gateway.spring.webflux.McpAuthorizationObservation;
import mcp.gateway.spring.webflux.McpGatewayAbuseProtectionEvaluator;
import mcp.gateway.spring.webflux.McpGatewayAuthorizationEvaluator;
import mcp.gateway.spring.webflux.McpGatewayAuthorizationMode;
import mcp.gateway.spring.webflux.McpGatewayCorrelationIdResolver;
import mcp.gateway.spring.webflux.McpGatewayWebFluxGovernanceFilter;
import mcp.gateway.spring.webflux.McpGatewayWebFluxProperties;
import mcp.gateway.spring.webflux.McpGrantedScopesExtractor;
import mcp.gateway.spring.webflux.McpInvalidRequestObserver;
import mcp.gateway.spring.webflux.McpJsonRpcToolInvocationParser;
import mcp.gateway.spring.webflux.McpProtectionRejectionObserver;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

public final class WebFluxSmoke {
    public static void main(String[] args) {
        McpJsonRpcToolInvocationParser parser = new McpJsonRpcToolInvocationParser(new ObjectMapper());
        McpToolInvocation invocation = parser.parse(("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"demo_tool"}}
                """).getBytes(StandardCharsets.UTF_8));

        require(invocation.kind() == McpToolInvocationKind.TOOL_CALL, "expected tool call invocation");
        require("demo_tool".equals(invocation.toolName()), "expected parsed tool name");

        AtomicReference<String> invalidReason = new AtomicReference<>();
        McpInvalidRequestObserver invalidObserver = (reason, requestId, correlationId) -> invalidReason.set(reason);
        invalidObserver.rejected("invalid_request_shape", "request-1", "correlation-1");
        require("invalid_request_shape".equals(invalidReason.get()), "expected invalid observer to run");

        McpGatewayAuthorizationEvaluator authorizationEvaluator = new McpGatewayAuthorizationEvaluator() {
            @Override
            public McpGatewayAuthorizationMode mode() {
                return McpGatewayAuthorizationMode.WARN;
            }

            @Override
            public ToolAuthorizationDecision authorize(Collection<String> grantedScopes,
                                                       GatewayToolExecutionContext context) {
                List<String> granted = List.copyOf(grantedScopes);
                boolean allowed = granted.contains("demo:run");
                return new ToolAuthorizationDecision(
                        allowed,
                        true,
                        context.actionName(),
                        List.of("demo:run"),
                        granted,
                        allowed ? List.of() : List.of("demo:run")
                );
            }
        };
        require(authorizationEvaluator.policy().enabled(), "WARN mode should enable authorization evaluation");

        McpGatewayAbuseProtectionEvaluator protectionEvaluator = new McpGatewayAbuseProtectionEvaluator() {
            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public McpAbuseProtectionDecision evaluate(GatewayToolExecutionContext context) {
                return McpAbuseProtectionDecision.allow(context.toolName(), context.principalId(), context.workspaceId());
            }
        };

        AtomicReference<McpAuthorizationObservation> authorizationObservation = new AtomicReference<>();
        McpGatewayWebFluxGovernanceFilter filter = new McpGatewayWebFluxGovernanceFilter(
                new ObjectMapper(),
                new McpGatewayWebFluxProperties("/mcp", 4096, 7),
                authorizationEvaluator,
                protectionEvaluator,
                (authentication, exchange, parsed) -> GatewayToolExecutionContext.of(
                        authentication == null ? null : authentication.getName(),
                        "workspace-a",
                        McpGatewayCorrelationIdResolver.defaultResolver().resolve(exchange),
                        parsed,
                        null
                ),
                McpGrantedScopesExtractor.springSecurityScopes(),
                authorizationObservation::set,
                McpProtectionRejectionObserver.noop(),
                McpGatewayCorrelationIdResolver.fromHeader("X-Correlation-Id"),
                invalidObserver
        );

        require(filter.getOrder() == 7, "expected configured filter order");

        String validBody = """
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"demo_tool"}}
                """;
        AtomicReference<String> downstreamBody = new AtomicReference<>();
        ServerWebExchange validExchange = MockServerWebExchange.from(MockServerHttpRequest.post("/mcp")
                        .header("X-Correlation-Id", "correlation-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(validBody))
                .mutate()
                .principal(Mono.just(new UsernamePasswordAuthenticationToken(
                        "demo-client",
                        "n/a",
                        List.of(new SimpleGrantedAuthority("SCOPE_demo:read"))
                )))
                .build();

        filter.filter(validExchange, captureBodyChain(downstreamBody)).block();
        require(validExchange.getResponse().getStatusCode() == null, "valid request should pass downstream");
        require(validBody.equals(downstreamBody.get()), "valid request body should be replayed downstream");
        McpAuthorizationObservation observedAuthorization = authorizationObservation.get();
        require(observedAuthorization != null, "expected authorization observation");
        require("demo_tool".equals(observedAuthorization.actionName()), "expected observed tool action");
        require("warn".equals(observedAuthorization.outcome()), "expected warn authorization observation");
        require("insufficient_scope".equals(observedAuthorization.reason()), "expected insufficient scope reason");
        require(List.of("demo:run").equals(observedAuthorization.requiredScopes()), "expected required scopes");
        require(List.of("demo:read").equals(observedAuthorization.grantedScopes()), "expected granted scopes");
        require("demo-client".equals(observedAuthorization.context().principalId()), "expected observed principal");
        require("workspace-a".equals(observedAuthorization.context().workspaceId()), "expected observed workspace");
        require("correlation-1".equals(observedAuthorization.context().correlationId()),
                "expected observed correlation id");

        AtomicReference<McpAbuseProtectionDecision> protectionRejection = new AtomicReference<>();
        McpGatewayWebFluxGovernanceFilter protectionFilter = new McpGatewayWebFluxGovernanceFilter(
                new ObjectMapper(),
                new McpGatewayWebFluxProperties("/mcp", 4096, 7),
                authorizationEvaluator,
                new McpGatewayAbuseProtectionEvaluator() {
                    @Override
                    public boolean enabled() {
                        return true;
                    }

                    @Override
                    public McpAbuseProtectionDecision evaluate(GatewayToolExecutionContext context) {
                        return McpAbuseProtectionDecision.reject(
                                "rate_limited",
                                "too many requests",
                                context.toolName(),
                                context.principalId(),
                                context.workspaceId(),
                                5
                        );
                    }
                },
                (authentication, exchange, parsed) -> GatewayToolExecutionContext.of(
                        authentication == null ? null : authentication.getName(),
                        "workspace-a",
                        McpGatewayCorrelationIdResolver.defaultResolver().resolve(exchange),
                        parsed,
                        null
                ),
                McpGrantedScopesExtractor.springSecurityScopes(),
                observation -> {
                },
                (decision, context) -> protectionRejection.set(decision),
                McpGatewayCorrelationIdResolver.fromHeader("X-Correlation-Id"),
                invalidObserver
        );
        ServerWebExchange protectedExchange = MockServerWebExchange.from(MockServerHttpRequest.post("/mcp")
                        .header("X-Correlation-Id", "correlation-protected")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(validBody))
                .mutate()
                .principal(Mono.just(new UsernamePasswordAuthenticationToken(
                        "demo-client",
                        "n/a",
                        List.of(new SimpleGrantedAuthority("SCOPE_demo:run"))
                )))
                .build();

        protectionFilter.filter(protectedExchange, ignored -> Mono.error(new IllegalStateException("must not call chain")))
                .block();
        require(protectedExchange.getResponse().getStatusCode().value() == 429,
                "protection rejection should return 429");
        require("5".equals(protectedExchange.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER)),
                "protection rejection should set Retry-After");
        require(protectionRejection.get() != null, "expected protection rejection observer");
        require("rate_limited".equals(protectionRejection.get().errorCode()),
                "expected observed protection error code");

        AtomicReference<String> rejectedReason = new AtomicReference<>();
        AtomicReference<String> rejectedRequestId = new AtomicReference<>();
        AtomicReference<String> rejectedCorrelationId = new AtomicReference<>();
        McpGatewayWebFluxGovernanceFilter invalidFilter = new McpGatewayWebFluxGovernanceFilter(
                new ObjectMapper(),
                new McpGatewayWebFluxProperties("/mcp", 4096, 7),
                authorizationEvaluator,
                protectionEvaluator,
                (authentication, exchange, parsed) -> GatewayToolExecutionContext.of(
                        authentication == null ? null : authentication.getName(),
                        "workspace-a",
                        McpGatewayCorrelationIdResolver.defaultResolver().resolve(exchange),
                        parsed,
                        null
                ),
                McpGrantedScopesExtractor.springSecurityScopes(),
                observation -> {
                },
                McpProtectionRejectionObserver.noop(),
                McpGatewayCorrelationIdResolver.fromHeader("X-Correlation-Id"),
                (reason, requestId, correlationId) -> {
                    rejectedReason.set(reason);
                    rejectedRequestId.set(requestId);
                    rejectedCorrelationId.set(correlationId);
                }
        );
        ServerWebExchange invalidExchange = MockServerWebExchange.from(MockServerHttpRequest.post("/mcp")
                        .header("X-Correlation-Id", "correlation-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":7}}"))
                .mutate()
                .principal(Mono.just(new UsernamePasswordAuthenticationToken(
                        "demo-client",
                        "n/a",
                        List.of(new SimpleGrantedAuthority("SCOPE_demo:run"))
                )))
                .build();

        invalidFilter.filter(invalidExchange, ignored -> Mono.error(new IllegalStateException("must not call chain")))
                .block();
        require(invalidExchange.getResponse().getStatusCode().value() == 400, "invalid request should be rejected");
        require("invalid_request_shape".equals(rejectedReason.get()), "invalid observer should receive reason");
        require(invalidExchange.getRequest().getId().equals(rejectedRequestId.get()),
                "invalid observer should receive HTTP request id");
        require("correlation-2".equals(rejectedCorrelationId.get()),
                "invalid observer should receive resolved correlation id");
        String invalidResponse = responseBody(invalidExchange);
        require(invalidResponse.contains("\"error\":\"invalid_json_rpc_request\""),
                "invalid response should use stable error code");
        require(invalidResponse.contains("\"reason\":\"invalid_request_shape\""),
                "invalid response should use stable reason");
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

    private static String responseBody(ServerWebExchange exchange) {
        return ((MockServerHttpResponse) exchange.getResponse()).getBodyAsString().block();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
JAVA

GATEWAY_CORE_STAGING_REPOSITORY="${STAGING_REPOSITORY}" \
  "${ROOT_DIR}/gradlew" -p "${WORK_DIR}" clean :core-consumer:run :webflux-consumer:run verifyGatewaySmokeResolution \
  --no-daemon --stacktrace

echo "Java 17 consumer smoke passed for core-only and WebFlux consumers using mcp-gateway artifacts ${VERSION}."
