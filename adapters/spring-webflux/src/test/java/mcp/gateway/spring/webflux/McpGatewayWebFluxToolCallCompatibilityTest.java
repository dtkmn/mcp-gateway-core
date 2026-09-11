package mcp.gateway.spring.webflux;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import mcp.gateway.core.authz.ToolAuthorizationDecision;
import mcp.gateway.core.context.GatewayToolExecutionContext;
import mcp.gateway.core.protection.McpAbuseProtectionDecision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
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
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins existing integrations' behavior without an authoritative active-tool registry.
 * A permission mapping alone cannot establish whether a tool is available.
 */
class McpGatewayWebFluxToolCallCompatibilityTest {
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    @ParameterizedTest
    @ValueSource(strings = {"\"request-雪\"", "\"\"", "0", "9007199254740993", "1.25"})
    void allowedCallPreservesExactBodyAndIdWithOneOrderedGovernancePass(String jsonId) {
        Harness harness = new Harness(McpGatewayAuthorizationMode.ENFORCE, decision(true, true), true);
        String body = toolCallBody(jsonId);
        ServerWebExchange exchange = exchange(body);

        StepVerifier.create(harness.filter.filter(exchange, harness.downstream())).verifyComplete();

        assertArrayEquals(body.getBytes(StandardCharsets.UTF_8), harness.downstreamBody.get());
        assertEquals(List.of("authorize", "protect", "observe", "downstream"), harness.calls);
        assertEquals(1, harness.observations.size());
        assertEquals("allowed", harness.observations.get(0).outcome());
        assertEquals("scope_granted", harness.observations.get(0).reason());
        assertEquals("demo_tool", harness.observations.get(0).actionName());
        assertEquals(HttpStatus.OK, exchange.getResponse().getStatusCode());
        assertFalse(exchange.getResponse().getHeaders().containsHeader(HttpHeaders.WWW_AUTHENTICATE));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void warningModeWithoutRegistryDoesNotInferToolExistenceFromPermissionMapping(boolean mapped) {
        Harness harness = new Harness(McpGatewayAuthorizationMode.WARN, decision(false, mapped), true);
        String body = toolCallBody("\"warn-call\"");
        ServerWebExchange exchange = exchange(body);

        StepVerifier.create(harness.filter.filter(exchange, harness.downstream())).verifyComplete();

        assertArrayEquals(body.getBytes(StandardCharsets.UTF_8), harness.downstreamBody.get());
        assertEquals(List.of("authorize", "protect", "observe", "downstream"), harness.calls);
        assertEquals(1, harness.observations.size());
        assertEquals("warn", harness.observations.get(0).outcome());
        assertEquals(mapped ? "insufficient_scope" : "unmapped_tool", harness.observations.get(0).reason());
        assertEquals(HttpStatus.OK, exchange.getResponse().getStatusCode());
        assertFalse(exchange.getResponse().getHeaders().containsHeader(HttpHeaders.WWW_AUTHENTICATE));
        assertEquals("", responseBody(exchange));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void disabledAuthorizationSkipsEvaluationAndObservationRegardlessOfProtection(boolean protectionEnabled) {
        Harness harness = new Harness(
                McpGatewayAuthorizationMode.DISABLED,
                decision(false, false),
                protectionEnabled
        );
        String body = toolCallBody("\"authorization-off\"");
        ServerWebExchange exchange = exchange(body);

        StepVerifier.create(harness.filter.filter(exchange, harness.downstream())).verifyComplete();

        assertArrayEquals(body.getBytes(StandardCharsets.UTF_8), harness.downstreamBody.get());
        assertEquals(protectionEnabled ? List.of("protect", "downstream") : List.of("downstream"), harness.calls);
        assertEquals(List.of(), harness.observations);
        assertEquals(HttpStatus.OK, exchange.getResponse().getStatusCode());
        assertFalse(exchange.getResponse().getHeaders().containsHeader(HttpHeaders.WWW_AUTHENTICATE));
    }

    @Test
    void enforcedMappedPermissionDenialRetainsScopeChallengeAndNeverReachesProtectionOrExecution() {
        Harness harness = new Harness(McpGatewayAuthorizationMode.ENFORCE, decision(false, true), true);
        ServerWebExchange exchange = exchange(toolCallBody("\"denied-call\""));

        StepVerifier.create(harness.filter.filter(exchange, harness.downstream())).verifyComplete();

        assertEquals(List.of("authorize", "observe"), harness.calls);
        assertNull(harness.downstreamBody.get());
        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        assertEquals(MediaType.APPLICATION_JSON, exchange.getResponse().getHeaders().getContentType());
        assertEquals(
                "Bearer error=\"insufficient_scope\", scope=\"demo:admin\"",
                exchange.getResponse().getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)
        );
        JsonNode response = JSON_MAPPER.readTree(responseBody(exchange));
        assertEquals("insufficient_scope", response.path("error").asString());
        assertEquals("demo_tool", response.path("tool").asString());
        assertEquals(JSON_MAPPER.valueToTree(List.of("demo:admin")), response.path("requiredScopes"));
        assertEquals(JSON_MAPPER.valueToTree(List.of("demo:run")), response.path("grantedScopes"));
        assertEquals(1, harness.observations.size());
        assertEquals("denied", harness.observations.get(0).outcome());
        assertEquals("insufficient_scope", harness.observations.get(0).reason());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":\"call-1\"}}"
    })
    void nonToolNotificationsSkipAuthorizationAndPreserveDownstreamAcknowledgement(String body) {
        Harness harness = new Harness(McpGatewayAuthorizationMode.ENFORCE, decision(false, false), true);
        ServerWebExchange exchange = exchange(body);
        StepVerifier.create(harness.filter.filter(exchange, harness.downstream(HttpStatus.ACCEPTED)))
                .verifyComplete();

        assertArrayEquals(body.getBytes(StandardCharsets.UTF_8), harness.downstreamBody.get());
        assertEquals(List.of("protect", "downstream"), harness.calls);
        assertEquals(List.of(), harness.observations);
        assertEquals(HttpStatus.ACCEPTED, exchange.getResponse().getStatusCode());
        assertFalse(exchange.getResponse().getHeaders().containsHeader(HttpHeaders.WWW_AUTHENTICATE));
        assertEquals("", responseBody(exchange));
    }

    private static ToolAuthorizationDecision decision(boolean allowed, boolean mapped) {
        List<String> requiredScopes = mapped ? List.of(allowed ? "demo:run" : "demo:admin") : List.of();
        return new ToolAuthorizationDecision(
                allowed,
                mapped,
                "demo_tool",
                requiredScopes,
                List.of("demo:run"),
                allowed ? List.of() : requiredScopes
        );
    }

    private static String toolCallBody(String jsonId) {
        return """
                {
                  "jsonrpc" : "2.0", "id" : %s,
                  "method" : "tools/call",
                  "params" : { "name" : "demo_tool", "arguments" : { "label" : "雪🌊" } }
                }
                """.formatted(jsonId);
    }

    private static ServerWebExchange exchange(String body) {
        return MockServerWebExchange.from(MockServerHttpRequest.post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body))
                .mutate()
                .principal(Mono.just(new UsernamePasswordAuthenticationToken(
                        "demo-client",
                        "not-used",
                        List.of(new SimpleGrantedAuthority("SCOPE_demo:run"))
                )))
                .build();
    }

    private static String responseBody(ServerWebExchange exchange) {
        return ((MockServerHttpResponse) exchange.getResponse()).getBodyAsString().block();
    }

    private static final class Harness {
        private final List<String> calls = new ArrayList<>();
        private final List<McpAuthorizationObservation> observations = new ArrayList<>();
        private final AtomicReference<byte[]> downstreamBody = new AtomicReference<>();
        private final McpGatewayWebFluxGovernanceFilter filter;

        private Harness(McpGatewayAuthorizationMode mode,
                        ToolAuthorizationDecision authorizationDecision,
                        boolean protectionEnabled) {
            filter = McpGatewayWebFluxGovernanceFilter.builder(
                            JSON_MAPPER,
                            (authentication, exchange, invocation) -> GatewayToolExecutionContext.of(
                                    authentication.getName(), "demo-workspace", "correlation-1", invocation, null
                            )
                    )
                    .authorization(() -> mode, (grantedScopes, context) -> {
                        calls.add("authorize");
                        return authorizationDecision;
                    })
                    .protection(() -> protectionEnabled, context -> {
                        calls.add("protect");
                        return McpAbuseProtectionDecision.allow(
                                context.actionName(), context.principalId(), context.workspaceId()
                        );
                    })
                    .authorizationObserver(observation -> {
                        calls.add("observe");
                        observations.add(observation);
                    })
                    .build();
        }

        private WebFilterChain downstream() {
            return downstream(HttpStatus.OK);
        }

        private WebFilterChain downstream(HttpStatus status) {
            return exchange -> {
                calls.add("downstream");
                exchange.getResponse().setStatusCode(status);
                return DataBufferUtils.join(exchange.getRequest().getBody())
                        .doOnNext(buffer -> {
                            try {
                                byte[] bytes = new byte[buffer.readableByteCount()];
                                buffer.read(bytes);
                                downstreamBody.set(bytes);
                            } finally {
                                DataBufferUtils.release(buffer);
                            }
                        })
                        .then(exchange.getResponse().setComplete());
            };
        }
    }
}
