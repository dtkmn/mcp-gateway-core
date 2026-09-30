package mcp.gateway.spring.webflux;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;
import mcp.gateway.core.authz.McpToolAccessRegistry;
import mcp.gateway.core.authz.McpToolAccessRule;
import mcp.gateway.core.authz.McpToolAuthorizer;
import mcp.gateway.core.context.GatewayToolExecutionContext;
import mcp.gateway.core.invocation.McpToolInvocation;
import mcp.gateway.core.invocation.McpToolInvocationKind;
import mcp.gateway.core.protection.McpAbuseProtectionDecision;
import mcp.gateway.core.tool.McpToolSurface;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class McpGatewayWebFluxContextResolverContractTest {
    private static final String TOOL_CALL = """
            {
              "jsonrpc": "2.0", "id": "context-test-id", "method": "tools/call",
              "params": {"name": "files.read", "arguments": {"note": "retain 雪"}}
            }
            """;
    private static final McpToolAccessRegistry ACCESS = McpToolAccessRegistry.of(List.of(
            McpToolAccessRule.of("files.read", McpToolSurface.GUIDED, List.of("files:read"))
    ));

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("invalidResolvedContexts")
    void rejectsInvalidResolverResultsBeforeScopesGovernanceObserversOrDispatch(Mode mode, InvalidResult invalidResult) {
        Harness harness = new Harness(mode, invocation -> invalidContext(invalidResult, invocation));
        ServerWebExchange exchange = exchange("/mcp", TOOL_CALL);
        exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"upstream\"");

        StepVerifier.create(harness.filter.filter(exchange, harness::downstream)).verifyComplete();

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exchange.getResponse().getStatusCode());
        assertEquals(MediaType.APPLICATION_JSON, exchange.getResponse().getHeaders().getContentType());
        assertFalse(exchange.getResponse().getHeaders().containsHeader(HttpHeaders.WWW_AUTHENTICATE));
        assertEquals("{\"error\":\"invalid_execution_context\"}",
                ((MockServerHttpResponse) exchange.getResponse()).getBodyAsString().block());
        assertEquals(List.of("context"), harness.calls);
        assertNull(harness.downstreamBody.get());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("activeModes")
    void acceptsEquivalentInvocationWithTrustedEnrichmentAndReplaysOriginalBytes(Mode mode) {
        AtomicReference<McpToolInvocation> parsedInvocation = new AtomicReference<>();
        AtomicReference<GatewayToolExecutionContext> resolvedContext = new AtomicReference<>();
        Harness harness = new Harness(mode, invocation -> {
            parsedInvocation.set(invocation);
            GatewayToolExecutionContext context = enrichedCopy(invocation);
            resolvedContext.set(context);
            return context;
        });
        ServerWebExchange exchange = exchange("/mcp", TOOL_CALL);

        StepVerifier.create(harness.filter.filter(exchange, harness::downstream)).verifyComplete();

        GatewayToolExecutionContext context = resolvedContext.get();
        assertNotSame(parsedInvocation.get(), context.invocation());
        assertEquals(parsedInvocation.get(), context.invocation());
        assertEquals("files.read", context.toolName());
        assertEquals("trusted-client", context.principalId());
        assertEquals("trusted-workspace", context.workspaceId());
        assertEquals("trusted-correlation", context.correlationId());
        assertEquals("runtime-selected-target", context.target());
        assertEquals(mode.authorizes() ? List.of(context) : List.of(), harness.authorizedContexts);
        assertEquals(mode.protection ? List.of(context) : List.of(), harness.protectedContexts);
        assertEquals(HttpStatus.OK, exchange.getResponse().getStatusCode());
        assertArrayEquals(TOOL_CALL.getBytes(StandardCharsets.UTF_8), harness.downstreamBody.get());

        List<String> expectedCalls = new ArrayList<>(List.of("context", "scopes"));
        if (mode.authorizes()) {
            expectedCalls.add("authorize");
        }
        if (mode.protection) {
            expectedCalls.add("protect");
        }
        if (mode.authorizes()) {
            expectedCalls.add("authorization-observation");
        }
        expectedCalls.add("downstream");
        assertEquals(expectedCalls, harness.calls);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nonAuthorizableRequests")
    void matchingInitializationAndNotificationContextsSkipAuthorizationButRunProtection(String method, String body) {
        Harness harness = new Harness(Mode.BOTH, McpGatewayWebFluxContextResolverContractTest::enrichedCopy);
        ServerWebExchange exchange = exchange("/mcp", body);

        StepVerifier.create(harness.filter.filter(exchange, harness::downstream)).verifyComplete();

        assertEquals(List.of("context", "scopes", "protect", "downstream"), harness.calls);
        assertEquals(List.of(), harness.authorizedContexts);
        assertEquals(1, harness.protectedContexts.size());
        McpToolInvocation invocation = harness.protectedContexts.get(0).invocation();
        assertEquals(McpToolInvocationKind.OTHER, invocation.kind());
        assertEquals(method, invocation.method());
        assertNull(invocation.toolName());
        assertArrayEquals(body.getBytes(StandardCharsets.UTF_8), harness.downstreamBody.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-json", "[{}]"})
    void inactiveGovernancePassesMalformedBodiesWithoutResolvingContext(String body) {
        Harness harness = new Harness(Mode.INACTIVE, unexpectedResolver());
        ServerWebExchange exchange = exchange("/mcp", body);

        StepVerifier.create(harness.filter.filter(exchange, harness::downstream)).verifyComplete();

        assertEquals(List.of("downstream"), harness.calls);
        assertSame(exchange, harness.downstreamExchange.get());
        assertArrayEquals(body.getBytes(StandardCharsets.UTF_8), harness.downstreamBody.get());
    }

    @Test
    void recognizedResponseEnvelopePassesWithoutResolvingContext() {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":\"server-request\",\"result\":null}";
        Harness harness = new Harness(Mode.BOTH, unexpectedResolver());
        ServerWebExchange exchange = exchange("/mcp", body);

        StepVerifier.create(harness.filter.filter(exchange, harness::downstream)).verifyComplete();

        assertEquals(List.of("downstream"), harness.calls);
        assertArrayEquals(body.getBytes(StandardCharsets.UTF_8), harness.downstreamBody.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/other", "/mcp/nested"})
    void offRouteRequestsPassWithoutResolvingContext(String path) {
        Harness harness = new Harness(Mode.BOTH, unexpectedResolver());
        ServerWebExchange exchange = exchange(path, TOOL_CALL);

        StepVerifier.create(harness.filter.filter(exchange, harness::downstream)).verifyComplete();

        assertEquals(List.of("downstream"), harness.calls);
        assertSame(exchange, harness.downstreamExchange.get());
        assertArrayEquals(TOOL_CALL.getBytes(StandardCharsets.UTF_8), harness.downstreamBody.get());
    }

    private static Stream<Arguments> invalidResolvedContexts() {
        return activeModes().flatMap(mode -> Arrays.stream(InvalidResult.values())
                .map(invalidResult -> Arguments.of(mode, invalidResult)));
    }

    private static Stream<Mode> activeModes() {
        return Arrays.stream(Mode.values()).filter(mode -> mode != Mode.INACTIVE);
    }

    private static Stream<Arguments> nonAuthorizableRequests() {
        return Stream.of(
                Arguments.of("initialize", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}"),
                Arguments.of("notifications/initialized",
                        "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")
        );
    }

    private static GatewayToolExecutionContext invalidContext(InvalidResult invalidResult, McpToolInvocation parsed) {
        if (invalidResult == InvalidResult.NULL_CONTEXT) {
            return null;
        }
        McpToolInvocation invocation = switch (invalidResult) {
            case NULL_CONTEXT -> throw new AssertionError("Handled above");
            case NULL_INVOCATION -> null;
            case WRONG_KIND -> new McpToolInvocation(McpToolInvocationKind.OTHER, parsed.method(), parsed.toolName());
            case WRONG_METHOD -> new McpToolInvocation(parsed.kind(), "tools/list", parsed.toolName());
            case WRONG_TOOL -> new McpToolInvocation(parsed.kind(), parsed.method(), "files.write");
        };
        return enrichedContext(invocation);
    }

    private static GatewayToolExecutionContext enrichedCopy(McpToolInvocation invocation) {
        return enrichedContext(new McpToolInvocation(invocation.kind(), invocation.method(), invocation.toolName()));
    }

    private static GatewayToolExecutionContext enrichedContext(McpToolInvocation invocation) {
        return GatewayToolExecutionContext.of(
                "trusted-client", "trusted-workspace", "trusted-correlation", invocation, "runtime-selected-target"
        );
    }

    private static Function<McpToolInvocation, GatewayToolExecutionContext> unexpectedResolver() {
        return invocation -> {
            throw new AssertionError("Context resolution must be skipped for this request");
        };
    }

    private static ServerWebExchange exchange(String path, String body) {
        return MockServerWebExchange.from(MockServerHttpRequest.post(path)
                .contentType(MediaType.APPLICATION_JSON).body(body));
    }

    private enum InvalidResult {
        NULL_CONTEXT, NULL_INVOCATION, WRONG_KIND, WRONG_METHOD, WRONG_TOOL
    }

    private enum Mode {
        ENFORCE(McpGatewayAuthorizationMode.ENFORCE, false, false),
        WARN(McpGatewayAuthorizationMode.WARN, false, false),
        PROTECTION_ONLY(McpGatewayAuthorizationMode.DISABLED, true, false),
        BOTH(McpGatewayAuthorizationMode.ENFORCE, true, false),
        REGISTRY_ONLY(McpGatewayAuthorizationMode.DISABLED, false, true),
        INACTIVE(McpGatewayAuthorizationMode.DISABLED, false, false);

        private final McpGatewayAuthorizationMode authorizationMode;
        private final boolean protection;
        private final boolean registry;

        Mode(McpGatewayAuthorizationMode authorizationMode, boolean protection, boolean registry) {
            this.authorizationMode = authorizationMode;
            this.protection = protection;
            this.registry = registry;
        }

        private boolean authorizes() {
            return authorizationMode != McpGatewayAuthorizationMode.DISABLED;
        }
    }

    private static final class Harness {
        private final List<String> calls = new ArrayList<>();
        private final List<GatewayToolExecutionContext> authorizedContexts = new ArrayList<>();
        private final List<GatewayToolExecutionContext> protectedContexts = new ArrayList<>();
        private final AtomicReference<byte[]> downstreamBody = new AtomicReference<>();
        private final AtomicReference<ServerWebExchange> downstreamExchange = new AtomicReference<>();
        private final McpGatewayWebFluxGovernanceFilter filter;

        private Harness(Mode mode, Function<McpToolInvocation, GatewayToolExecutionContext> resolve) {
            McpToolAuthorizer authorizer = McpToolAuthorizer.of(ACCESS, List.of("mcp:tools:list"));
            McpGatewayWebFluxGovernanceFilter.Builder builder = McpGatewayWebFluxGovernanceFilter.builder(
                            JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build(),
                            (authentication, exchange, invocation) -> {
                                calls.add("context");
                                return resolve.apply(invocation);
                            }
                    )
                    .grantedScopesExtractor(authentication -> {
                        calls.add("scopes");
                        return List.of("files:read");
                    })
                    .authorization(() -> mode.authorizationMode, (scopes, context) -> {
                        calls.add("authorize");
                        authorizedContexts.add(context);
                        return authorizer.authorize(context, scopes, false, true);
                    })
                    .protection(() -> mode.protection, context -> {
                        calls.add("protect");
                        protectedContexts.add(context);
                        return McpAbuseProtectionDecision.allow(
                                context.toolName(), context.principalId(), context.workspaceId());
                    })
                    .authorizationObserver(observation -> calls.add("authorization-observation"))
                    .protectionRejectionObserver((decision, context) -> calls.add("protection-observation"))
                    .invalidRequestObserver((reason, requestId, correlationId) -> calls.add("invalid-observation"));
            if (mode.registry) {
                builder.toolRegistry(ACCESS.toolRegistry());
            }
            filter = builder.build();
        }

        private Mono<Void> downstream(ServerWebExchange exchange) {
            calls.add("downstream");
            downstreamExchange.set(exchange);
            exchange.getResponse().setStatusCode(HttpStatus.OK);
            return DataBufferUtils.join(exchange.getRequest().getBody())
                    .doOnNext(buffer -> {
                        byte[] bytes = new byte[buffer.readableByteCount()];
                        buffer.read(bytes);
                        DataBufferUtils.release(buffer);
                        downstreamBody.set(bytes);
                    })
                    .then(exchange.getResponse().setComplete());
        }
    }
}
