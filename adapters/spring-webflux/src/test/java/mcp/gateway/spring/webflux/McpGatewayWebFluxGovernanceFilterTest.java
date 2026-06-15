package mcp.gateway.spring.webflux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import mcp.gateway.core.authz.ToolAuthorizationDecision;
import mcp.gateway.core.context.GatewayToolExecutionContext;
import mcp.gateway.core.governance.GatewayToolAuthorizationPolicy;
import mcp.gateway.core.protection.McpAbuseProtectionDecision;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class McpGatewayWebFluxGovernanceFilterTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final McpGatewayWebFluxProperties PROPERTIES =
            new McpGatewayWebFluxProperties("/mcp", 4096, 10, 11);

    @Test
    void allowsRequestAndPreservesBodyAfterOneGovernancePass() {
        List<McpAuthorizationObservation> observations = new ArrayList<>();
        AtomicReference<String> downstreamBody = new AtomicReference<>();
        McpGatewayWebFluxGovernanceFilter filter = new McpGatewayWebFluxGovernanceFilter(
                OBJECT_MAPPER,
                PROPERTIES,
                authorization(McpGatewayAuthorizationMode.ENFORCE, authAllowed()),
                protection(McpAbuseProtectionDecision.allow("demo_tool", "demo-client", "demo-workspace")),
                contextResolver(),
                McpGrantedScopesExtractor.springSecurityScopes(),
                observations::add,
                McpProtectionRejectionObserver.noop(),
                McpGatewayCorrelationIdResolver.defaultResolver()
        );
        String body = toolCallBody();

        StepVerifier.create(filter.filter(exchange(body, authentication("demo-client", "SCOPE_demo:run")),
                        captureBodyChain(downstreamBody)))
                .verifyComplete();

        assertEquals(body, downstreamBody.get());
        assertEquals(1, observations.size());
        assertEquals("allowed", observations.get(0).outcome());
        assertEquals("scope_granted", observations.get(0).reason());
        assertEquals("demo-client", observations.get(0).context().principalId());
    }

    @Test
    void authorizationRejectsBeforeProtectionRuns() {
        AtomicBoolean protectionCalled = new AtomicBoolean(false);
        AtomicBoolean downstreamCalled = new AtomicBoolean(false);
        List<McpAuthorizationObservation> observations = new ArrayList<>();
        McpGatewayWebFluxGovernanceFilter filter = new McpGatewayWebFluxGovernanceFilter(
                OBJECT_MAPPER,
                PROPERTIES,
                authorization(McpGatewayAuthorizationMode.ENFORCE, authDenied()),
                new McpGatewayAbuseProtectionEvaluator() {
                    @Override
                    public boolean enabled() {
                        return true;
                    }

                    @Override
                    public McpAbuseProtectionDecision evaluate(GatewayToolExecutionContext context) {
                        protectionCalled.set(true);
                        return McpAbuseProtectionDecision.allow("demo_tool", "demo-client", "demo-workspace");
                    }
                },
                contextResolver(),
                McpGrantedScopesExtractor.springSecurityScopes(),
                observations::add,
                McpProtectionRejectionObserver.noop(),
                McpGatewayCorrelationIdResolver.defaultResolver()
        );
        ServerWebExchange exchange = exchange(toolCallBody(), authentication("demo-client", "SCOPE_demo:read"));

        StepVerifier.create(filter.filter(exchange, markCalledChain(downstreamCalled)))
                .verifyComplete();

        assertFalse(downstreamCalled.get());
        assertFalse(protectionCalled.get());
        assertEquals(1, observations.size());
        assertEquals("denied", observations.get(0).outcome());
        assertEquals("insufficient_scope", observations.get(0).reason());
        assertEquals(403, exchange.getResponse().getStatusCode().value());
        assertTrue(responseBody(exchange).contains("\"error\":\"insufficient_scope\""));
    }

    @Test
    void warnAuthorizationCanStillBeRejectedByProtection() {
        List<McpAuthorizationObservation> observations = new ArrayList<>();
        List<McpAbuseProtectionDecision> rejections = new ArrayList<>();
        AtomicBoolean downstreamCalled = new AtomicBoolean(false);
        McpGatewayWebFluxGovernanceFilter filter = new McpGatewayWebFluxGovernanceFilter(
                OBJECT_MAPPER,
                PROPERTIES,
                authorization(McpGatewayAuthorizationMode.WARN, authDenied()),
                protection(McpAbuseProtectionDecision.reject(
                        "rate_limited",
                        "too many requests",
                        "demo_tool",
                        "demo-client",
                        "demo-workspace",
                        7
                )),
                contextResolver(),
                McpGrantedScopesExtractor.springSecurityScopes(),
                observations::add,
                (decision, context) -> rejections.add(decision),
                McpGatewayCorrelationIdResolver.defaultResolver()
        );
        ServerWebExchange exchange = exchange(toolCallBody(), authentication("demo-client", "SCOPE_demo:read"));

        StepVerifier.create(filter.filter(exchange, markCalledChain(downstreamCalled))).verifyComplete();

        assertFalse(downstreamCalled.get());
        assertEquals(1, observations.size());
        assertEquals("warn", observations.get(0).outcome());
        assertEquals("insufficient_scope", observations.get(0).reason());
        assertEquals(1, rejections.size());
        assertEquals(429, exchange.getResponse().getStatusCode().value());
        assertEquals("7", exchange.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
        assertTrue(responseBody(exchange).contains("\"error\":\"rate_limited\""));
    }

    @Test
    void nullAuthorizationPolicyFailsClosedBeforeAuthorizationProtectionOrDownstream() {
        AtomicBoolean authorizationCalled = new AtomicBoolean(false);
        AtomicBoolean protectionCalled = new AtomicBoolean(false);
        AtomicBoolean downstreamCalled = new AtomicBoolean(false);
        McpGatewayWebFluxGovernanceFilter filter = new McpGatewayWebFluxGovernanceFilter(
                OBJECT_MAPPER,
                PROPERTIES,
                new McpGatewayAuthorizationEvaluator() {
                    @Override
                    public McpGatewayAuthorizationMode mode() {
                        return McpGatewayAuthorizationMode.ENFORCE;
                    }

                    @Override
                    public GatewayToolAuthorizationPolicy policy() {
                        return null;
                    }

                    @Override
                    public ToolAuthorizationDecision authorize(Collection<String> grantedScopes,
                                                               GatewayToolExecutionContext context) {
                        authorizationCalled.set(true);
                        return authAllowed();
                    }
                },
                new McpGatewayAbuseProtectionEvaluator() {
                    @Override
                    public boolean enabled() {
                        return true;
                    }

                    @Override
                    public McpAbuseProtectionDecision evaluate(GatewayToolExecutionContext context) {
                        protectionCalled.set(true);
                        return McpAbuseProtectionDecision.allow("demo_tool", "demo-client", "demo-workspace");
                    }
                },
                contextResolver()
        );

        NullPointerException failure = assertThrows(NullPointerException.class, () -> filter.filter(
                exchange(toolCallBody(), authentication("demo-client", "SCOPE_demo:run")),
                markCalledChain(downstreamCalled)
        ));

        assertEquals("authorization evaluator policy must not be null", failure.getMessage());
        assertFalse(authorizationCalled.get());
        assertFalse(protectionCalled.get());
        assertFalse(downstreamCalled.get());
    }

    @Test
    void authorizationPolicyControlsGovernanceWhenLegacyEnabledHelperIsWrong() {
        AtomicBoolean authorizationCalled = new AtomicBoolean(false);
        AtomicBoolean downstreamCalled = new AtomicBoolean(false);
        List<McpAuthorizationObservation> observations = new ArrayList<>();
        McpGatewayWebFluxGovernanceFilter filter = new McpGatewayWebFluxGovernanceFilter(
                OBJECT_MAPPER,
                PROPERTIES,
                new McpGatewayAuthorizationEvaluator() {
                    @Override
                    public McpGatewayAuthorizationMode mode() {
                        return McpGatewayAuthorizationMode.ENFORCE;
                    }

                    @Override
                    public GatewayToolAuthorizationPolicy policy() {
                        return GatewayToolAuthorizationPolicy.enforce();
                    }

                    @Override
                    public boolean enabled() {
                        return false;
                    }

                    @Override
                    public ToolAuthorizationDecision authorize(Collection<String> grantedScopes,
                                                               GatewayToolExecutionContext context) {
                        authorizationCalled.set(true);
                        return authDenied();
                    }
                },
                null,
                contextResolver(),
                McpGrantedScopesExtractor.springSecurityScopes(),
                observations::add,
                McpProtectionRejectionObserver.noop(),
                McpGatewayCorrelationIdResolver.defaultResolver()
        );
        ServerWebExchange exchange = exchange(toolCallBody(), authentication("demo-client", "SCOPE_demo:read"));

        StepVerifier.create(filter.filter(exchange, markCalledChain(downstreamCalled))).verifyComplete();

        assertTrue(authorizationCalled.get());
        assertFalse(downstreamCalled.get());
        assertEquals(1, observations.size());
        assertEquals("denied", observations.get(0).outcome());
        assertEquals("insufficient_scope", observations.get(0).reason());
        assertEquals(403, exchange.getResponse().getStatusCode().value());
    }

    @Test
    void protectionOnlyModeStillEvaluatesAnonymousRequests() {
        AtomicReference<GatewayToolExecutionContext> context = new AtomicReference<>();
        McpGatewayWebFluxGovernanceFilter filter = new McpGatewayWebFluxGovernanceFilter(
                OBJECT_MAPPER,
                PROPERTIES,
                authorization(McpGatewayAuthorizationMode.DISABLED, authDenied()),
                new McpGatewayAbuseProtectionEvaluator() {
                    @Override
                    public boolean enabled() {
                        return true;
                    }

                    @Override
                    public McpAbuseProtectionDecision evaluate(GatewayToolExecutionContext requestContext) {
                        context.set(requestContext);
                        return McpAbuseProtectionDecision.allow("demo_tool", null, "demo-workspace");
                    }
                },
                contextResolver()
        );

        StepVerifier.create(filter.filter(exchangeWithoutPrincipal(toolCallBody()), ignored -> Mono.empty()))
                .verifyComplete();

        assertEquals("anonymous", context.get().principalId());
    }

    @Test
    void rejectsOversizedBodyBeforeGovernanceRuns() {
        AtomicBoolean authorizationCalled = new AtomicBoolean(false);
        McpGatewayWebFluxGovernanceFilter filter = new McpGatewayWebFluxGovernanceFilter(
                OBJECT_MAPPER,
                new McpGatewayWebFluxProperties("/mcp", 1024, 10, 11),
                new McpGatewayAuthorizationEvaluator() {
                    @Override
                    public McpGatewayAuthorizationMode mode() {
                        return McpGatewayAuthorizationMode.ENFORCE;
                    }

                    @Override
                    public ToolAuthorizationDecision authorize(Collection<String> grantedScopes,
                                                               GatewayToolExecutionContext context) {
                        authorizationCalled.set(true);
                        return authAllowed();
                    }
                },
                protection(McpAbuseProtectionDecision.allow("demo_tool", "demo-client", "demo-workspace")),
                contextResolver()
        );
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/mcp")
                        .header("X-Correlation-Id", "corr-1")
                        .header(HttpHeaders.CONTENT_LENGTH, "4096")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(toolCallBody()))
                .mutate()
                .principal(Mono.just(authentication("demo-client", "SCOPE_demo:run")))
                .build();

        StepVerifier.create(filter.filter(exchange, ignored -> Mono.error(new AssertionError("must not call chain"))))
                .verifyComplete();

        assertFalse(authorizationCalled.get());
        assertEquals(413, exchange.getResponse().getStatusCode().value());
        assertTrue(responseBody(exchange).contains("\"error\":\"request_body_too_large\""));
    }

    private static McpGatewayAuthorizationEvaluator authorization(McpGatewayAuthorizationMode mode,
                                                                 ToolAuthorizationDecision decision) {
        return new McpGatewayAuthorizationEvaluator() {
            @Override
            public McpGatewayAuthorizationMode mode() {
                return mode;
            }

            @Override
            public ToolAuthorizationDecision authorize(Collection<String> grantedScopes,
                                                       GatewayToolExecutionContext context) {
                return decision;
            }
        };
    }

    private static McpGatewayAbuseProtectionEvaluator protection(McpAbuseProtectionDecision decision) {
        return new McpGatewayAbuseProtectionEvaluator() {
            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public McpAbuseProtectionDecision evaluate(GatewayToolExecutionContext context) {
                return decision;
            }
        };
    }

    private static McpGatewayWebFluxContextResolver contextResolver() {
        return (authentication, exchange, invocation) -> GatewayToolExecutionContext.of(
                authentication == null ? null : authentication.getName(),
                "demo-workspace",
                exchange.getRequest().getHeaders().getFirst("X-Correlation-Id"),
                invocation,
                null
        );
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

    private static WebFilterChain markCalledChain(AtomicBoolean called) {
        return exchange -> {
            called.set(true);
            return Mono.empty();
        };
    }

    private static ServerWebExchange exchange(String body, Authentication authentication) {
        return MockServerWebExchange.from(MockServerHttpRequest.post("/mcp")
                        .header("X-Correlation-Id", "corr-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body))
                .mutate()
                .principal(Mono.just(authentication))
                .build();
    }

    private static ServerWebExchange exchangeWithoutPrincipal(String body) {
        return MockServerWebExchange.from(MockServerHttpRequest.post("/mcp")
                .header("X-Correlation-Id", "corr-1")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body));
    }

    private static Authentication authentication(String name, String authority) {
        return new UsernamePasswordAuthenticationToken(name, "n/a", List.of(new SimpleGrantedAuthority(authority)));
    }

    private static String toolCallBody() {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"demo_tool\"}}";
    }

    private static String responseBody(ServerWebExchange exchange) {
        return ((MockServerHttpResponse) exchange.getResponse()).getBodyAsString().block();
    }

    private ToolAuthorizationDecision authAllowed() {
        return new ToolAuthorizationDecision(
                true,
                true,
                "demo_tool",
                List.of("demo:run"),
                List.of("demo:run"),
                List.of()
        );
    }

    private ToolAuthorizationDecision authDenied() {
        return new ToolAuthorizationDecision(
                false,
                true,
                "demo_tool",
                List.of("demo:run"),
                List.of("demo:read"),
                List.of("demo:run")
        );
    }

}
