package mcp.gateway.spring.webflux;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import mcp.gateway.core.authz.ToolAuthorizationDecision;
import mcp.gateway.core.context.GatewayToolExecutionContext;
import mcp.gateway.core.invocation.McpToolInvocation;
import mcp.gateway.core.protection.McpAbuseProtectionDecision;
import mcp.gateway.core.tool.McpToolDescriptor;
import mcp.gateway.core.tool.McpToolRegistry;
import mcp.gateway.core.tool.McpToolSurface;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class McpGatewayWebFluxAdapterRejectionObserverTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String RPC_ID = "client-json-rpc-id";
    private static final String FALLBACK_CORRELATION = "fallback-correlation";
    private static final String CONTEXT_CORRELATION = "context-correlation";
    private static final String VALID_CALL = toolCall("active_tool", "\"" + RPC_ID + "\"");

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("registryRejections")
    void observesRegistryRejectionsBeforePrincipalOrGovernanceInEveryMode(
            McpGatewayAuthorizationMode mode, McpAdapterRejectionReason reason) {
        Harness harness = new Harness();
        harness.mode = mode;
        ServerWebExchange exchange = harness.exchange("/mcp", bodyFor(reason));

        StepVerifier.create(harness.apply(exchange)).verifyComplete();

        assertEarlyRejection(harness, exchange, reason, FALLBACK_CORRELATION);
        assertEquals(List.of("correlation", "adapter"), harness.calls);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void invalidContextUsesFallbackCorrelationBeforeScopeExtraction(boolean nullContext) {
        Harness harness = new Harness();
        harness.nullContext = nullContext;
        harness.mismatchedContext = !nullContext;
        ServerWebExchange exchange = harness.exchange("/mcp", VALID_CALL);

        StepVerifier.create(harness.apply(exchange)).verifyComplete();

        assertEarlyRejection(harness, exchange, McpAdapterRejectionReason.INVALID_EXECUTION_CONTEXT,
                FALLBACK_CORRELATION);
        assertEquals(List.of("principal", "context", "correlation", "adapter"), harness.calls);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void unmappedEnforcedToolUsesValidContextCorrelationOrFallback(boolean contextHasCorrelation) {
        Harness harness = new Harness();
        harness.mapped = false;
        harness.contextCorrelation = contextHasCorrelation ? CONTEXT_CORRELATION : null;
        ServerWebExchange exchange = harness.exchange("/mcp", VALID_CALL);

        StepVerifier.create(harness.apply(exchange)).verifyComplete();

        assertEarlyRejection(harness, exchange, McpAdapterRejectionReason.UNMAPPED_TOOL,
                contextHasCorrelation ? CONTEXT_CORRELATION : FALLBACK_CORRELATION);
        assertEquals(contextHasCorrelation
                        ? List.of("principal", "context", "scopes", "authorize", "adapter")
                        : List.of("principal", "context", "scopes", "authorize", "correlation", "adapter"),
                harness.calls);
    }

    @ParameterizedTest
    @EnumSource(value = McpGatewayAuthorizationMode.class, names = {"WARN", "DISABLED"})
    void unmappedWarningOrDisabledAuthorizationDoesNotEmitAnAdapterRejection(McpGatewayAuthorizationMode mode) {
        Harness harness = new Harness();
        harness.mode = mode;
        harness.mapped = false;
        ServerWebExchange exchange = harness.exchange("/mcp", VALID_CALL);

        StepVerifier.create(harness.apply(exchange)).verifyComplete();

        assertArrayEquals(VALID_CALL.getBytes(StandardCharsets.UTF_8), harness.downstreamBody.get());
        assertEquals(List.of(), harness.adapterObservations);
        assertEquals(List.of(), harness.invalidObservations);
        assertEquals(List.of(), harness.protectionObservations);
        if (mode == McpGatewayAuthorizationMode.WARN) {
            assertEquals(List.of("principal", "context", "scopes", "authorize", "protect", "authorization", "downstream"),
                    harness.calls);
            assertEquals("warn", harness.authorizationObservations.get(0).outcome());
            assertEquals("unmapped_tool", harness.authorizationObservations.get(0).reason());
        } else {
            assertEquals(List.of("principal", "context", "scopes", "protect", "downstream"), harness.calls);
            assertEquals(List.of(), harness.authorizationObservations);
        }
    }

    @Test
    void equalReconstructedInvocationContinuesGovernanceAndReplaysOriginalBody() {
        Harness harness = new Harness();
        ServerWebExchange exchange = harness.exchange("/mcp", VALID_CALL);

        StepVerifier.create(harness.apply(exchange)).verifyComplete();

        assertEquals(List.of("principal", "context", "scopes", "authorize", "protect", "authorization", "downstream"),
                harness.calls);
        assertEquals(List.of(), harness.adapterObservations);
        assertEquals("allowed", harness.authorizationObservations.get(0).outcome());
        assertArrayEquals(VALID_CALL.getBytes(StandardCharsets.UTF_8), harness.downstreamBody.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void malformedAndOversizedBodiesUseOnlyTheExistingInvalidRequestObserver(boolean oversized) {
        Harness harness = new Harness();
        ServerWebExchange exchange = harness.exchange("/mcp", oversized ? "x".repeat(2_048) : "not-json");

        StepVerifier.create(harness.apply(exchange)).verifyComplete();

        assertEquals(oversized ? HttpStatus.CONTENT_TOO_LARGE : HttpStatus.BAD_REQUEST,
                exchange.getResponse().getStatusCode());
        assertEquals(List.of(oversized ? "request_body_too_large" : "invalid_json_rpc_request"),
                harness.invalidObservations);
        assertEquals(List.of("correlation", "invalid"), harness.calls);
        assertEquals(List.of(), harness.adapterObservations);
        assertEquals(List.of(), harness.authorizationObservations);
        assertEquals(List.of(), harness.protectionObservations);
        assertNull(harness.downstreamBody.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void authorizationAndProtectionDenialsKeepTheirExistingObserversOnly(boolean authorizationDenied) {
        Harness harness = new Harness();
        harness.allowed = !authorizationDenied;
        harness.protectionDenied = !authorizationDenied;
        ServerWebExchange exchange = harness.exchange("/mcp", VALID_CALL);

        StepVerifier.create(harness.apply(exchange)).verifyComplete();

        assertEquals(authorizationDenied ? HttpStatus.FORBIDDEN : HttpStatus.TOO_MANY_REQUESTS,
                exchange.getResponse().getStatusCode());
        assertEquals(1, harness.authorizationObservations.size());
        assertEquals(authorizationDenied ? "denied" : "allowed", harness.authorizationObservations.get(0).outcome());
        assertEquals(authorizationDenied ? 0 : 1, harness.protectionObservations.size());
        assertEquals(List.of(), harness.adapterObservations);
        assertEquals(List.of(), harness.invalidObservations);
        assertNull(harness.downstreamBody.get());
    }

    @ParameterizedTest
    @EnumSource(McpAdapterRejectionReason.class)
    void omittedObserverDoesNotResolveCorrelationForPreviouslySilentRejections(McpAdapterRejectionReason reason) {
        Harness harness = new Harness();
        harness.observeAdapter = false;
        harness.nullContext = reason == McpAdapterRejectionReason.INVALID_EXECUTION_CONTEXT;
        harness.mapped = reason != McpAdapterRejectionReason.UNMAPPED_TOOL;
        harness.contextCorrelation = null;
        harness.correlationFailure = new IllegalStateException("Fallback must not run for an omitted observer");
        ServerWebExchange exchange = harness.exchange("/mcp", bodyFor(reason));

        StepVerifier.create(harness.apply(exchange)).verifyComplete();

        assertWireResponse(exchange, reason);
        assertFalse(harness.calls.contains("correlation"));
        assertEquals(List.of(), harness.adapterObservations);
        assertEquals(List.of(), harness.invalidObservations);
        assertEquals(List.of(), harness.authorizationObservations);
        assertEquals(List.of(), harness.protectionObservations);
        assertNull(harness.downstreamBody.get());
    }

    @Test
    void correlationFailurePropagatesBeforeTheInstalledObserverOrResponseRuns() {
        Harness harness = new Harness();
        IllegalStateException failure = new IllegalStateException("correlation failed");
        harness.correlationFailure = failure;
        ServerWebExchange exchange = harness.exchange("/mcp", bodyFor(McpAdapterRejectionReason.UNKNOWN_TOOL));

        StepVerifier.create(harness.apply(exchange)).expectErrorSatisfies(error -> assertSame(failure, error)).verify();

        assertEquals(List.of("correlation"), harness.calls);
        assertEquals(List.of(), harness.adapterObservations);
        assertNull(harness.downstreamBody.get());
        assertNull(exchange.getResponse().getStatusCode());
        assertFalse(exchange.getResponse().isCommitted());
    }

    @Test
    void explicitlyInstallingNoopObserverStillOptsIntoCorrelationResolution() {
        Harness harness = new Harness();
        harness.explicitObserver = McpAdapterRejectionObserver.noop();
        IllegalStateException failure = new IllegalStateException("correlation failed for explicit noop");
        harness.correlationFailure = failure;
        ServerWebExchange exchange = harness.exchange("/mcp", bodyFor(McpAdapterRejectionReason.UNKNOWN_TOOL));

        StepVerifier.create(harness.apply(exchange)).expectErrorSatisfies(error -> assertSame(failure, error)).verify();

        assertEquals(List.of("correlation"), harness.calls);
        assertEquals(List.of(), harness.adapterObservations);
        assertNull(harness.downstreamBody.get());
        assertNull(exchange.getResponse().getStatusCode());
        assertFalse(exchange.getResponse().isCommitted());
    }

    @Test
    void observerExceptionPropagatesWithoutDispatchingOrWritingTheRejectionResponse() {
        Harness harness = new Harness();
        IllegalStateException failure = new IllegalStateException("observer failed");
        harness.observerFailure = failure;
        ServerWebExchange exchange = harness.exchange("/mcp", bodyFor(McpAdapterRejectionReason.UNKNOWN_TOOL));

        StepVerifier.create(harness.apply(exchange)).expectErrorSatisfies(error -> assertSame(failure, error)).verify();

        assertEquals(List.of("correlation", "adapter"), harness.calls);
        assertEquals(1, harness.adapterObservations.size());
        assertNull(harness.downstreamBody.get());
        assertNull(exchange.getResponse().getStatusCode());
        assertFalse(exchange.getResponse().isCommitted());
    }

    @Test
    void resolverExceptionIsNotMisreportedAsAnInvalidExecutionContext() {
        Harness harness = new Harness();
        IllegalStateException failure = new IllegalStateException("resolver failed");
        harness.contextFailure = failure;
        ServerWebExchange exchange = harness.exchange("/mcp", VALID_CALL);

        StepVerifier.create(harness.apply(exchange)).expectErrorSatisfies(error -> assertSame(failure, error)).verify();

        assertEquals(List.of("principal", "context"), harness.calls);
        assertEquals(List.of(), harness.adapterObservations);
        assertEquals(List.of(), harness.invalidObservations);
        assertEquals(List.of(), harness.authorizationObservations);
        assertEquals(List.of(), harness.protectionObservations);
        assertNull(harness.downstreamBody.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"response", "off-route", "inactive"})
    void bypassedRequestsDoNotEmitOrResolveObservations(String bypass) {
        Harness harness = new Harness();
        String body = "response".equals(bypass)
                ? "{\"jsonrpc\":\"2.0\",\"id\":\"server-request\",\"result\":null}" : "not-json";
        if ("inactive".equals(bypass)) {
            harness.registryEnabled = false;
            harness.mode = McpGatewayAuthorizationMode.DISABLED;
            harness.protectionEnabled = false;
        }
        harness.contextFailure = new IllegalStateException("Context must not be resolved");
        harness.correlationFailure = new IllegalStateException("Correlation must not be resolved");
        ServerWebExchange exchange = harness.exchange("off-route".equals(bypass) ? "/other" : "/mcp", body);

        StepVerifier.create(harness.apply(exchange)).verifyComplete();

        assertEquals(List.of("downstream"), harness.calls);
        assertArrayEquals(body.getBytes(StandardCharsets.UTF_8), harness.downstreamBody.get());
        assertEquals(List.of(), harness.adapterObservations);
        assertEquals(List.of(), harness.invalidObservations);
        assertEquals(List.of(), harness.authorizationObservations);
        assertEquals(List.of(), harness.protectionObservations);
    }

    @Test
    void builderRejectsAnExplicitNullObserver() {
        assertThrows(NullPointerException.class, () -> McpGatewayWebFluxGovernanceFilter
                .builder(JSON, (authentication, exchange, invocation) -> null).adapterRejectionObserver(null));
    }

    @Test
    void adapterObserverAloneDoesNotSatisfyTheGovernanceConfigurationRequirement() {
        assertThrows(IllegalStateException.class, () -> McpGatewayWebFluxGovernanceFilter
                .builder(JSON, (authentication, exchange, invocation) -> null)
                .adapterRejectionObserver(McpAdapterRejectionObserver.noop())
                .build());
    }

    private static Stream<Arguments> registryRejections() {
        return Arrays.stream(McpGatewayAuthorizationMode.values()).flatMap(mode -> Stream.of(
                McpAdapterRejectionReason.INVALID_TOOL_CALL_ID,
                McpAdapterRejectionReason.TOOL_CALL_WITHOUT_ID,
                McpAdapterRejectionReason.UNKNOWN_TOOL).map(reason -> Arguments.of(mode, reason)));
    }

    private static String bodyFor(McpAdapterRejectionReason reason) {
        return switch (reason) {
            case INVALID_TOOL_CALL_ID -> toolCall("unavailable_tool", "true");
            case TOOL_CALL_WITHOUT_ID -> "{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"params\":{\"name\":\"unavailable_tool\"}}";
            case UNKNOWN_TOOL -> toolCall("unavailable_tool", "\"" + RPC_ID + "\"");
            case UNMAPPED_TOOL, INVALID_EXECUTION_CONTEXT -> VALID_CALL;
        };
    }

    private static String toolCall(String name, String id) {
        return """
                { "jsonrpc": "2.0", "id": %s, "method": "tools/call",
                  "params": {"name": "%s", "arguments": {"privateNote": "retain 雪"}} }
                """.formatted(id, name);
    }

    private static void assertEarlyRejection(Harness harness, ServerWebExchange exchange,
                                             McpAdapterRejectionReason reason, String correlation) {
        assertEquals(List.of(new Observation(reason, exchange.getRequest().getId(), correlation)),
                harness.adapterObservations);
        assertNotEquals(RPC_ID, harness.adapterObservations.get(0).requestId());
        String code = switch (reason) {
            case INVALID_TOOL_CALL_ID -> "invalid_tool_call_id";
            case TOOL_CALL_WITHOUT_ID -> "tool_call_without_id";
            case UNKNOWN_TOOL -> "unknown_tool";
            case UNMAPPED_TOOL -> "unmapped_tool";
            case INVALID_EXECUTION_CONTEXT -> "invalid_execution_context";
        };
        assertEquals(code, reason.code());
        assertEquals(List.of(), harness.invalidObservations);
        assertEquals(List.of(), harness.authorizationObservations);
        assertEquals(List.of(), harness.protectionObservations);
        assertNull(harness.downstreamBody.get());
        assertWireResponse(exchange, reason);
    }

    private static void assertWireResponse(ServerWebExchange exchange, McpAdapterRejectionReason reason) {
        HttpStatus status = switch (reason) {
            case INVALID_TOOL_CALL_ID -> HttpStatus.BAD_REQUEST;
            case TOOL_CALL_WITHOUT_ID -> HttpStatus.ACCEPTED;
            case UNKNOWN_TOOL, UNMAPPED_TOOL -> HttpStatus.OK;
            case INVALID_EXECUTION_CONTEXT -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        String expected = switch (reason) {
            case INVALID_TOOL_CALL_ID -> "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32600,\"message\":\"Invalid Request\"}}";
            case TOOL_CALL_WITHOUT_ID -> "";
            case UNKNOWN_TOOL -> "{\"jsonrpc\":\"2.0\",\"id\":\"" + RPC_ID + "\",\"error\":{\"code\":-32602,\"message\":\"Unknown tool\"}}";
            case UNMAPPED_TOOL -> "{\"jsonrpc\":\"2.0\",\"id\":\"" + RPC_ID + "\",\"error\":{\"code\":-32603,\"message\":\"Internal error\"}}";
            case INVALID_EXECUTION_CONTEXT -> "{\"error\":\"invalid_execution_context\"}";
        };
        assertEquals(status, exchange.getResponse().getStatusCode());
        assertEquals(expected, ((MockServerHttpResponse) exchange.getResponse()).getBodyAsString().block());
        assertFalse(exchange.getResponse().getHeaders().containsHeader(HttpHeaders.WWW_AUTHENTICATE));
        assertEquals(reason == McpAdapterRejectionReason.TOOL_CALL_WITHOUT_ID ? null : MediaType.APPLICATION_JSON,
                exchange.getResponse().getHeaders().getContentType());
    }

    private record Observation(McpAdapterRejectionReason reason, String requestId, String correlationId) { }

    private static final class Harness {
        private final List<String> calls = new ArrayList<>();
        private final List<Observation> adapterObservations = new ArrayList<>();
        private final List<String> invalidObservations = new ArrayList<>();
        private final List<McpAuthorizationObservation> authorizationObservations = new ArrayList<>();
        private final List<McpAbuseProtectionDecision> protectionObservations = new ArrayList<>();
        private final AtomicReference<byte[]> downstreamBody = new AtomicReference<>();
        private McpGatewayAuthorizationMode mode = McpGatewayAuthorizationMode.ENFORCE;
        private boolean registryEnabled = true;
        private boolean protectionEnabled = true;
        private boolean observeAdapter = true;
        private boolean mapped = true;
        private boolean allowed = true;
        private boolean protectionDenied;
        private boolean nullContext;
        private boolean mismatchedContext;
        private String contextCorrelation = CONTEXT_CORRELATION;
        private RuntimeException correlationFailure;
        private RuntimeException observerFailure;
        private RuntimeException contextFailure;
        private McpAdapterRejectionObserver explicitObserver;

        private Mono<Void> apply(ServerWebExchange exchange) {
            McpGatewayWebFluxGovernanceFilter.Builder builder = McpGatewayWebFluxGovernanceFilter.builder(JSON,
                            (authentication, request, parsed) -> {
                                calls.add("context");
                                if (contextFailure != null) {
                                    throw contextFailure;
                                }
                                if (nullContext) {
                                    return null;
                                }
                                McpToolInvocation copied = new McpToolInvocation(parsed.kind(), parsed.method(),
                                        mismatchedContext ? "different_tool" : parsed.toolName());
                                return GatewayToolExecutionContext.of("trusted-client", "workspace", contextCorrelation,
                                        copied, "trusted-target");
                            })
                    .properties(new McpGatewayWebFluxProperties("/mcp", 1_024, 0))
                    .authorization(() -> mode, (scopes, context) -> {
                        calls.add("authorize");
                        return new ToolAuthorizationDecision(allowed, mapped, context.toolName(), List.of("demo:run"),
                                List.copyOf(scopes), allowed ? List.of() : List.of("demo:run"));
                    })
                    .protection(() -> protectionEnabled, context -> {
                        calls.add("protect");
                        return protectionDenied
                                ? McpAbuseProtectionDecision.reject("rate_limited", "quota", context.toolName(),
                                        context.principalId(), context.workspaceId(), 17)
                                : McpAbuseProtectionDecision.allow(context.toolName(), context.principalId(), context.workspaceId());
                    })
                    .grantedScopesExtractor(authentication -> {
                        calls.add("scopes");
                        return List.of("demo:run");
                    })
                    .authorizationObserver(observation -> {
                        calls.add("authorization");
                        authorizationObservations.add(observation);
                    })
                    .protectionRejectionObserver((decision, context) -> {
                        calls.add("protection-rejection");
                        protectionObservations.add(decision);
                    })
                    .invalidRequestObserver((reason, requestId, correlationId) -> {
                        calls.add("invalid");
                        invalidObservations.add(reason);
                    })
                    .correlationIdResolver(request -> {
                        calls.add("correlation");
                        if (correlationFailure != null) {
                            throw correlationFailure;
                        }
                        return FALLBACK_CORRELATION;
                    });
            if (registryEnabled) {
                builder.toolRegistry(McpToolRegistry.of(List.of(
                        McpToolDescriptor.builder("active_tool", McpToolSurface.GUIDED).build())));
            }
            if (explicitObserver != null) {
                builder.adapterRejectionObserver(explicitObserver);
            } else if (observeAdapter) {
                builder.adapterRejectionObserver((reason, requestId, correlationId) -> {
                    calls.add("adapter");
                    adapterObservations.add(new Observation(reason, requestId, correlationId));
                    if (observerFailure != null) {
                        throw observerFailure;
                    }
                });
            }
            return builder.build().filter(exchange, this::downstream);
        }

        private ServerWebExchange exchange(String path, String body) {
            ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post(path)
                            .contentType(MediaType.APPLICATION_JSON).body(body)).mutate()
                    .principal(Mono.defer(() -> {
                        calls.add("principal");
                        return Mono.just(new UsernamePasswordAuthenticationToken("trusted-client", "unused", List.of()));
                    })).build();
            exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"upstream\"");
            return exchange;
        }

        private Mono<Void> downstream(ServerWebExchange exchange) {
            calls.add("downstream");
            exchange.getResponse().setStatusCode(HttpStatus.OK);
            return DataBufferUtils.join(exchange.getRequest().getBody()).doOnNext(buffer -> {
                try {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    downstreamBody.set(bytes);
                } finally {
                    DataBufferUtils.release(buffer);
                }
            }).then(exchange.getResponse().setComplete());
        }
    }
}
