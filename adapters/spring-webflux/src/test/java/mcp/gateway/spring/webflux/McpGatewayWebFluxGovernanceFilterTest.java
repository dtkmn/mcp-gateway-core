package mcp.gateway.spring.webflux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import mcp.gateway.core.authz.ToolAuthorizationDecision;
import mcp.gateway.core.context.GatewayToolExecutionContext;
import mcp.gateway.core.governance.GatewayToolAuthorizationPolicy;
import mcp.gateway.core.protection.McpAbuseProtectionDecision;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class McpGatewayWebFluxGovernanceFilterTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final McpGatewayWebFluxProperties PROPERTIES =
            new McpGatewayWebFluxProperties("/mcp", 4096, 10);

    @Test
    void getOrderUsesConfiguredGovernanceFilterOrder() {
        McpGatewayWebFluxGovernanceFilter filter = new McpGatewayWebFluxGovernanceFilter(
                OBJECT_MAPPER,
                new McpGatewayWebFluxProperties("/mcp", 4096, 123),
                null,
                null,
                contextResolver()
        );

        assertEquals(123, filter.getOrder());
    }

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
    void authorizationIsSkippedForNonAuthorizableInvocationsButProtectionStillRuns() {
        AtomicBoolean authorizationCalled = new AtomicBoolean(false);
        AtomicBoolean protectionCalled = new AtomicBoolean(false);
        AtomicBoolean downstreamCalled = new AtomicBoolean(false);
        AtomicReference<GatewayToolExecutionContext> protectedContext = new AtomicReference<>();
        McpGatewayWebFluxGovernanceFilter filter = new McpGatewayWebFluxGovernanceFilter(
                OBJECT_MAPPER,
                PROPERTIES,
                new McpGatewayAuthorizationEvaluator() {
                    @Override
                    public McpGatewayAuthorizationMode mode() {
                        return McpGatewayAuthorizationMode.ENFORCE;
                    }

                    @Override
                    public ToolAuthorizationDecision authorize(Collection<String> grantedScopes,
                                                               GatewayToolExecutionContext context) {
                        authorizationCalled.set(true);
                        return authDenied();
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
                        protectedContext.set(context);
                        return McpAbuseProtectionDecision.allow("ping", "demo-client", "demo-workspace");
                    }
                },
                contextResolver()
        );
        ServerWebExchange exchange = exchange("""
                {"jsonrpc":"2.0","id":1,"method":"ping"}
                """, authentication("demo-client", "SCOPE_demo:read"));

        StepVerifier.create(filter.filter(exchange, markCalledChain(downstreamCalled))).verifyComplete();

        assertFalse(authorizationCalled.get());
        assertTrue(protectionCalled.get());
        assertEquals("ping", protectedContext.get().actionName());
        assertTrue(downstreamCalled.get());
    }

    @Test
    void governedRequestsDoNotRequireJsonRpcVersionField() {
        AtomicReference<String> downstreamBody = new AtomicReference<>();
        McpGatewayWebFluxGovernanceFilter filter = new McpGatewayWebFluxGovernanceFilter(
                OBJECT_MAPPER,
                PROPERTIES,
                authorization(McpGatewayAuthorizationMode.ENFORCE, authAllowed()),
                null,
                contextResolver()
        );
        String body = "{\"method\":\"tools/list\"}";

        StepVerifier.create(filter.filter(exchange(body, authentication("demo-client", "SCOPE_mcp:tools:list")),
                        captureBodyChain(downstreamBody)))
                .verifyComplete();

        assertEquals(body, downstreamBody.get());
    }

    @Test
    void rejectsInvalidRequestShapesWhenAnyGovernanceIsActive() throws Exception {
        List<McpGatewayWebFluxGovernanceFilter> filters = List.of(
                new McpGatewayWebFluxGovernanceFilter(
                        OBJECT_MAPPER,
                        PROPERTIES,
                        authorization(McpGatewayAuthorizationMode.ENFORCE, authAllowed()),
                        null,
                        contextResolver()
                ),
                new McpGatewayWebFluxGovernanceFilter(
                        OBJECT_MAPPER,
                        PROPERTIES,
                        authorization(McpGatewayAuthorizationMode.DISABLED, authAllowed()),
                        protection(McpAbuseProtectionDecision.allow("demo_tool", "demo-client", "demo-workspace")),
                        contextResolver()
                ),
                new McpGatewayWebFluxGovernanceFilter(
                        OBJECT_MAPPER,
                        PROPERTIES,
                        authorization(McpGatewayAuthorizationMode.WARN, authAllowed()),
                        protection(McpAbuseProtectionDecision.allow("demo_tool", "demo-client", "demo-workspace")),
                        contextResolver()
                )
        );

        for (McpGatewayWebFluxGovernanceFilter filter : filters) {
            AtomicBoolean downstreamCalled = new AtomicBoolean(false);
            ServerWebExchange exchange = exchange("not-json", authentication("demo-client", "SCOPE_demo:run"));

            StepVerifier.create(filter.filter(exchange, markCalledChain(downstreamCalled))).verifyComplete();

            assertFalse(downstreamCalled.get());
            assertInvalidRequestResponse(exchange, McpJsonRpcRequestRejectionReason.MALFORMED_JSON);
        }
    }

    @Test
    void invalidRequestDoesNotRunPrincipalContextScopesAuthorizationOrProtection() throws Exception {
        AtomicBoolean principalResolved = new AtomicBoolean(false);
        AtomicBoolean contextResolved = new AtomicBoolean(false);
        AtomicBoolean scopesExtracted = new AtomicBoolean(false);
        AtomicBoolean authorizationCalled = new AtomicBoolean(false);
        AtomicBoolean protectionCalled = new AtomicBoolean(false);
        AtomicBoolean downstreamCalled = new AtomicBoolean(false);
        AtomicReference<String> observedReason = new AtomicReference<>();
        AtomicReference<String> observedRequestId = new AtomicReference<>();
        AtomicReference<String> observedCorrelationId = new AtomicReference<>();
        McpGatewayWebFluxGovernanceFilter filter = new McpGatewayWebFluxGovernanceFilter(
                OBJECT_MAPPER,
                PROPERTIES,
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
                (authentication, exchange, invocation) -> {
                    contextResolved.set(true);
                    return contextResolver().resolve(authentication, exchange, invocation);
                },
                authentication -> {
                    scopesExtracted.set(true);
                    return List.of("demo:run");
                },
                McpAuthorizationObserver.noop(),
                McpProtectionRejectionObserver.noop(),
                McpGatewayCorrelationIdResolver.defaultResolver(),
                (reason, requestId, correlationId) -> {
                    observedReason.set(reason);
                    observedRequestId.set(requestId);
                    observedCorrelationId.set(correlationId);
                }
        );
        ServerWebExchange exchange = exchangeWithPrincipal(
                "{\"jsonrpc\":\"2.0\",\"id\":99,\"method\":\"tools/call\",\"params\":{\"name\":7}}",
                Mono.defer(() -> {
                    principalResolved.set(true);
                    return Mono.just(authentication("demo-client", "SCOPE_demo:run"));
                })
        );

        StepVerifier.create(filter.filter(exchange, markCalledChain(downstreamCalled))).verifyComplete();

        assertFalse(principalResolved.get());
        assertFalse(contextResolved.get());
        assertFalse(scopesExtracted.get());
        assertFalse(authorizationCalled.get());
        assertFalse(protectionCalled.get());
        assertFalse(downstreamCalled.get());
        assertEquals("invalid_request_shape", observedReason.get());
        assertEquals(exchange.getRequest().getId(), observedRequestId.get());
        assertEquals("corr-1", observedCorrelationId.get());
        assertInvalidRequestResponse(exchange, McpJsonRpcRequestRejectionReason.INVALID_TOOL_NAME);
        JsonNode body = OBJECT_MAPPER.readTree(responseBody(exchange));
        assertEquals(exchange.getRequest().getId(), body.get("requestId").asText());
        assertFalse("99".equals(body.get("requestId").asText()));
    }

    @Test
    void invalidAndOversizedBodiesPassThroughExactlyWhenGovernanceIsInactive() {
        McpGatewayWebFluxGovernanceFilter filter = new McpGatewayWebFluxGovernanceFilter(
                OBJECT_MAPPER,
                new McpGatewayWebFluxProperties("/mcp", 1024, 10),
                authorization(McpGatewayAuthorizationMode.DISABLED, authDenied()),
                null,
                (authentication, exchange, invocation) -> {
                    throw new AssertionError("context resolver must not run when governance is inactive");
                }
        );

        assertPassesBodyThroughExactly(filter, "not-json");
        assertPassesBodyThroughExactly(filter, "[{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\"}]");
        assertPassesBodyThroughExactly(filter, toolCallBody());

        AtomicReference<String> oversizedDownstreamBody = new AtomicReference<>();
        String body = "x".repeat(2048);
        ServerWebExchange oversizedExchange = exchangeWithContentLength(
                body,
                "4096",
                authentication("demo-client", "SCOPE_demo:run")
        );
        StepVerifier.create(filter.filter(oversizedExchange, captureBodyChain(oversizedDownstreamBody)))
                .verifyComplete();

        assertEquals(body, oversizedDownstreamBody.get());
        assertEquals(null, oversizedExchange.getResponse().getStatusCode());
    }

    @Test
    void rejectsOversizedBodyBeforeGovernanceRuns() {
        AtomicBoolean authorizationCalled = new AtomicBoolean(false);
        McpGatewayWebFluxGovernanceFilter filter = new McpGatewayWebFluxGovernanceFilter(
                OBJECT_MAPPER,
                new McpGatewayWebFluxProperties("/mcp", 1024, 10),
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

    @Test
    void rejectsStreamingBodyThatExceedsReadLimitBeforeGovernanceRuns() {
        AtomicBoolean authorizationCalled = new AtomicBoolean(false);
        AtomicBoolean protectionCalled = new AtomicBoolean(false);
        McpGatewayWebFluxGovernanceFilter filter = new McpGatewayWebFluxGovernanceFilter(
                OBJECT_MAPPER,
                new McpGatewayWebFluxProperties("/mcp", 1024, 10),
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
        ServerWebExchange exchange = streamingExchangeWithoutContentLength(
                "x".repeat(2048),
                authentication("demo-client", "SCOPE_demo:run")
        );

        StepVerifier.create(filter.filter(exchange, ignored -> Mono.error(new AssertionError("must not call chain"))))
                .verifyComplete();

        assertFalse(authorizationCalled.get());
        assertFalse(protectionCalled.get());
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

    private static void assertPassesBodyThroughExactly(McpGatewayWebFluxGovernanceFilter filter, String body) {
        AtomicReference<String> downstreamBody = new AtomicReference<>();
        ServerWebExchange exchange = exchange(body, authentication("demo-client", "SCOPE_demo:run"));

        StepVerifier.create(filter.filter(exchange, captureBodyChain(downstreamBody)))
                .verifyComplete();

        assertEquals(body, downstreamBody.get());
        assertEquals(null, exchange.getResponse().getStatusCode());
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

    private static ServerWebExchange exchangeWithPrincipal(String body, Mono<Principal> principal) {
        return MockServerWebExchange.from(MockServerHttpRequest.post("/mcp")
                        .header("X-Correlation-Id", "corr-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body))
                .mutate()
                .principal(principal)
                .build();
    }

    private static ServerWebExchange exchangeWithContentLength(String body,
                                                              String contentLength,
                                                              Authentication authentication) {
        return MockServerWebExchange.from(MockServerHttpRequest.post("/mcp")
                        .header("X-Correlation-Id", "corr-1")
                        .header(HttpHeaders.CONTENT_LENGTH, contentLength)
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

    private static ServerWebExchange streamingExchangeWithoutContentLength(String body, Authentication authentication) {
        DefaultDataBufferFactory bufferFactory = new DefaultDataBufferFactory();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        int splitAt = 12;
        byte[] firstChunk = Arrays.copyOfRange(bytes, 0, splitAt);
        byte[] secondChunk = Arrays.copyOfRange(bytes, splitAt, bytes.length);
        return MockServerWebExchange.from(MockServerHttpRequest.post("/mcp")
                        .header("X-Correlation-Id", "corr-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Flux.just(
                                bufferFactory.wrap(firstChunk),
                                bufferFactory.wrap(secondChunk)
                        )))
                .mutate()
                .principal(Mono.just(authentication))
                .build();
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

    private static void assertInvalidRequestResponse(ServerWebExchange exchange,
                                                     McpJsonRpcRequestRejectionReason reason) throws Exception {
        assertEquals(400, exchange.getResponse().getStatusCode().value());
        assertTrue(exchange.getResponse().getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON));
        JsonNode body = OBJECT_MAPPER.readTree(responseBody(exchange));
        Instant.parse(body.get("timestamp").asText());
        assertEquals(400, body.get("status").asInt());
        assertEquals("invalid_json_rpc_request", body.get("error").asText());
        assertEquals(reason.code(), body.get("reason").asText());
        assertFalse(body.has("message"));
        assertEquals("corr-1", body.get("correlationId").asText());
        assertEquals(exchange.getRequest().getId(), body.get("requestId").asText());
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
