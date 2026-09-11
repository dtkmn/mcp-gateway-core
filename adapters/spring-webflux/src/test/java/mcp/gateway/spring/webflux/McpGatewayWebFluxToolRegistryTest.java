package mcp.gateway.spring.webflux;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import mcp.gateway.core.authz.McpToolAccessRegistry;
import mcp.gateway.core.authz.McpToolAccessRule;
import mcp.gateway.core.authz.McpToolAuthorizer;
import mcp.gateway.core.authz.ToolAuthorizationDecision;
import mcp.gateway.core.context.GatewayToolExecutionContext;
import mcp.gateway.core.protection.McpAbuseProtectionDecision;
import mcp.gateway.core.tool.McpToolDescriptor;
import mcp.gateway.core.tool.McpToolRegistry;
import mcp.gateway.core.tool.McpToolSurface;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An explicitly configured active-tool registry is authoritative for availability,
 * independently of authorization mappings or enforcement mode.
 */
class McpGatewayWebFluxToolRegistryTest {
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();
    private static final String CORRELATION_ID = "registry-correlation";
    private static final McpToolSurface TEST_SURFACE = McpToolSurface.of("test");

    @ParameterizedTest
    @EnumSource(McpGatewayAuthorizationMode.class)
    void unavailableToolStopsBeforePrincipalPermissionsAndProtectionInEveryMode(McpGatewayAuthorizationMode mode) {
        Harness harness = new Harness(mode, decision(true, true), true, registry());
        ServerWebExchange exchange = harness.exchange(toolCall("unavailable_tool", "\"request-1\""));

        StepVerifier.create(harness.filter.filter(exchange, harness.downstream())).verifyComplete();

        assertProtocolError(exchange, HttpStatus.OK, "\"request-1\"", -32602, "Unknown tool");
        assertEquals(List.of(), harness.calls);
        assertNull(harness.downstreamBody.get());
        assertEquals(List.of(), harness.authorizationObservations);
    }

    @Test
    void unknownAndDisabledToolsHaveIdenticalPublicStatusHeadersAndBody() {
        Harness unknown = new Harness(McpGatewayAuthorizationMode.ENFORCE, decision(false, false), true,
                registry("active_tool"));
        Harness disabled = new Harness(McpGatewayAuthorizationMode.ENFORCE, decision(true, true), true,
                registry("active_tool"));
        ServerWebExchange unknownExchange = unknown.exchange(toolCall("never_registered", "77"));
        ServerWebExchange disabledExchange = disabled.exchange(toolCall("disabled_tool", "77"));

        StepVerifier.create(unknown.filter.filter(unknownExchange, unknown.downstream())).verifyComplete();
        StepVerifier.create(disabled.filter.filter(disabledExchange, disabled.downstream())).verifyComplete();

        assertProtocolError(unknownExchange, HttpStatus.OK, "77", -32602, "Unknown tool");
        assertEquals(unknownExchange.getResponse().getStatusCode(), disabledExchange.getResponse().getStatusCode());
        assertEquals(unknownExchange.getResponse().getHeaders(), disabledExchange.getResponse().getHeaders());
        assertEquals(responseBody(unknownExchange), responseBody(disabledExchange));
        assertEquals(List.of(), unknown.authorizationObservations);
        assertEquals(List.of(), disabled.authorizationObservations);
        assertNull(unknown.downstreamBody.get());
        assertNull(disabled.downstreamBody.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"request-雪🌊\"", "\"\"", "0", "-1", "9007199254740993", "9223372036854775808"})
    void unknownToolPreservesStringOrIntegerRequestIdWithoutPrecisionLoss(String jsonId) {
        Harness harness = new Harness(McpGatewayAuthorizationMode.ENFORCE, decision(false, false), false,
                registry());
        ServerWebExchange exchange = harness.exchange(toolCall("unavailable_tool", jsonId));

        StepVerifier.create(harness.filter.filter(exchange, harness.downstream())).verifyComplete();

        assertProtocolError(exchange, HttpStatus.OK, jsonId, -32602, "Unknown tool");
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "true", "false", "{}", "[]", "1.25"})
    void invalidExplicitToolCallIdIsRejectedBeforeGovernance(String jsonId) {
        Harness harness = new Harness(McpGatewayAuthorizationMode.DISABLED, decision(true, true), false,
                registry("active_tool"));
        ServerWebExchange exchange = harness.exchange(toolCall("active_tool", jsonId));

        StepVerifier.create(harness.filter.filter(exchange, harness.downstream())).verifyComplete();

        assertProtocolError(exchange, HttpStatus.BAD_REQUEST, "null", -32600, "Invalid Request");
        assertEquals(List.of(), harness.calls);
        assertNull(harness.downstreamBody.get());
    }

    @Test
    void toolCallWithoutIdIsAcknowledgedWithoutResponseOrExecution() {
        Harness harness = new Harness(McpGatewayAuthorizationMode.ENFORCE, decision(true, true), true,
                registry("active_tool"));
        ServerWebExchange exchange = harness.exchange("""
                {"jsonrpc":"2.0","method":"tools/call","params":{"name":"active_tool"}}
                """);

        StepVerifier.create(harness.filter.filter(exchange, harness.downstream())).verifyComplete();

        assertEquals(HttpStatus.ACCEPTED, exchange.getResponse().getStatusCode());
        assertEquals("", responseBody(exchange));
        assertFalse(exchange.getResponse().getHeaders().containsHeader(HttpHeaders.WWW_AUTHENTICATE));
        assertEquals(List.of(), harness.calls);
        assertNull(harness.downstreamBody.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void knownToolMissingMappingUnderEnforcementIsConfigurationErrorEvenIfEvaluatorSaysAllowed(boolean allowed) {
        Harness harness = new Harness(McpGatewayAuthorizationMode.ENFORCE, decision(allowed, false), true,
                registry("active_tool"));
        ServerWebExchange exchange = harness.exchange(toolCall("active_tool", "\"configuration-id\""));

        StepVerifier.create(harness.filter.filter(exchange, harness.downstream())).verifyComplete();

        assertProtocolError(exchange, HttpStatus.OK, "\"configuration-id\"", -32603, "Internal error");
        assertEquals(List.of("principal", "context", "scopes", "authorize"),
                harness.calls);

        assertEquals(List.of(), harness.authorizationObservations);
        assertNull(harness.downstreamBody.get());
    }

    @ParameterizedTest
    @EnumSource(value = McpGatewayAuthorizationMode.class, names = {"WARN", "DISABLED"})
    void knownUnmappedToolRetainsWarningAndDisabledAuthorizationBehavior(McpGatewayAuthorizationMode mode) {
        Harness harness = new Harness(mode, decision(false, false), true, registry("active_tool"));
        String body = toolCall("active_tool", "\"unmapped-mode\"");
        ServerWebExchange exchange = harness.exchange(body);

        StepVerifier.create(harness.filter.filter(exchange, harness.downstream())).verifyComplete();

        assertArrayEquals(body.getBytes(StandardCharsets.UTF_8), harness.downstreamBody.get());
        assertEquals(HttpStatus.OK, exchange.getResponse().getStatusCode());
        if (mode == McpGatewayAuthorizationMode.WARN) {
            assertEquals(List.of("principal", "context", "scopes", "authorize", "protect",
                    "observe", "downstream"), harness.calls);
            assertEquals(1, harness.authorizationObservations.size());
            assertEquals("warn", harness.authorizationObservations.get(0).outcome());
            assertEquals("unmapped_tool", harness.authorizationObservations.get(0).reason());
        } else {
            assertEquals(List.of("principal", "context", "scopes", "protect", "downstream"),
                    harness.calls);
            assertEquals(List.of(), harness.authorizationObservations);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"request-雪\"", "0", "9007199254740993"})
    void activeAllowedToolRunsUnchangedGovernanceAndExecutionOnce(String jsonId) {
        Harness harness = new Harness(McpGatewayAuthorizationMode.ENFORCE, decision(true, true), true,
                registry("active_tool"));
        String body = toolCall("active_tool", jsonId);
        ServerWebExchange exchange = harness.exchange(body);

        StepVerifier.create(harness.filter.filter(exchange, harness.downstream())).verifyComplete();

        assertArrayEquals(body.getBytes(StandardCharsets.UTF_8), harness.downstreamBody.get());
        assertEquals(List.of("principal", "context", "scopes", "authorize", "protect",
                "observe", "downstream"), harness.calls);
        assertEquals(1, harness.authorizationObservations.size());
        assertEquals("allowed", harness.authorizationObservations.get(0).outcome());
        assertEquals(HttpStatus.OK, exchange.getResponse().getStatusCode());
        assertFalse(exchange.getResponse().getHeaders().containsHeader(HttpHeaders.WWW_AUTHENTICATE));
    }

    @Test
    void activeMappedDeniedToolRetainsPermissionChallengeAndDoesNotRunProtectionOrExecution() {
        Harness harness = new Harness(McpGatewayAuthorizationMode.ENFORCE, decision(false, true), true,
                registry("active_tool"));
        ServerWebExchange exchange = harness.exchange(toolCall("active_tool", "19"));

        StepVerifier.create(harness.filter.filter(exchange, harness.downstream())).verifyComplete();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        assertEquals("Bearer error=\"insufficient_scope\", scope=\"demo:admin\"",
                exchange.getResponse().getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE));
        assertEquals("insufficient_scope", JSON_MAPPER.readTree(responseBody(exchange)).path("error").asString());
        assertEquals(List.of("principal", "context", "scopes", "authorize", "observe"), harness.calls);
        assertEquals(1, harness.authorizationObservations.size());
        assertEquals("denied", harness.authorizationObservations.get(0).outcome());
        assertNull(harness.downstreamBody.get());
    }

    @Test
    void activeAllowedToolStillReceivesProtectionRejectionWithoutExecution() {
        Harness harness = new Harness(McpGatewayAuthorizationMode.ENFORCE, decision(true, true), true,
                registry("active_tool"));
        harness.protectionDecision.set(McpAbuseProtectionDecision.reject("rate_limited", "Too many calls",
                "active_tool", "demo-client", "workspace", 17));
        ServerWebExchange exchange = harness.exchange(toolCall("active_tool", "20"));

        StepVerifier.create(harness.filter.filter(exchange, harness.downstream())).verifyComplete();

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exchange.getResponse().getStatusCode());
        assertEquals("17", exchange.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
        assertFalse(exchange.getResponse().getHeaders().containsHeader(HttpHeaders.WWW_AUTHENTICATE));
        assertEquals("rate_limited", JSON_MAPPER.readTree(responseBody(exchange)).path("error").asString());
        assertEquals(List.of("principal", "context", "scopes", "authorize", "protect",
                "observe", "protection-reject"), harness.calls);
        assertEquals(1, harness.authorizationObservations.size());
        assertEquals("allowed", harness.authorizationObservations.get(0).outcome());
        assertNull(harness.downstreamBody.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "PUT", "DELETE"})
    void nonPostRequestsBypassRegistryAndPreserveDownstreamBody(String method) {
        Harness harness = new Harness(McpGatewayAuthorizationMode.ENFORCE, decision(false, false), true,
                registry());
        String body = toolCall("unknown", "1");
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .method(HttpMethod.valueOf(method), "/mcp").contentType(MediaType.APPLICATION_JSON).body(body));

        StepVerifier.create(harness.filter.filter(exchange, harness.downstream())).verifyComplete();

        assertArrayEquals(body.getBytes(StandardCharsets.UTF_8), harness.downstreamBody.get());
        assertEquals(List.of("downstream"), harness.calls);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/other", "/mcp/nested", "/mcp-other"})
    void offRouteRequestsBypassRegistryAndPreserveDownstreamBody(String path) {
        Harness harness = new Harness(McpGatewayAuthorizationMode.ENFORCE, decision(false, false), true,
                registry());
        String body = toolCall("unknown", "1");
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post(path)
                .contentType(MediaType.APPLICATION_JSON).body(body));

        StepVerifier.create(harness.filter.filter(exchange, harness.downstream())).verifyComplete();

        assertArrayEquals(body.getBytes(StandardCharsets.UTF_8), harness.downstreamBody.get());
        assertEquals(List.of("downstream"), harness.calls);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-json", "{}", "[]", "{\"method\":\"tools/call\",\"params\":{}}"})
    void registryOnlyConfigurationKeepsMalformedRequestGuardBeforeContextResolution(String body) {
        List<String> invalidReasons = new ArrayList<>();
        McpGatewayWebFluxGovernanceFilter filter = guardedRegistryOnlyFilter(invalidReasons);
        ServerWebExchange exchange = basicExchange(body);

        StepVerifier.create(filter.filter(exchange, ignored -> Mono.error(new AssertionError("Must not execute"))))
                .verifyComplete();

        assertEquals(HttpStatus.BAD_REQUEST, exchange.getResponse().getStatusCode());
        assertEquals("invalid_json_rpc_request", JSON_MAPPER.readTree(responseBody(exchange)).path("error").asString());
        assertEquals(1, invalidReasons.size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void registryOnlyConfigurationKeepsBodyLimitWithOrWithoutContentLength(boolean declareLength) {
        List<String> invalidReasons = new ArrayList<>();
        McpGatewayWebFluxGovernanceFilter filter = guardedRegistryOnlyFilter(invalidReasons);
        String body = "x".repeat(1025);
        MockServerHttpRequest.BodyBuilder request = MockServerHttpRequest.post("/mcp")
                .contentType(MediaType.APPLICATION_JSON);
        if (declareLength) {
            request.contentLength(body.getBytes(StandardCharsets.UTF_8).length);
        }
        DefaultDataBufferFactory bufferFactory = new DefaultDataBufferFactory();
        ServerWebExchange exchange = MockServerWebExchange.from(declareLength ? request.body(body) : request.body(Flux.just(
                bufferFactory.wrap(body.substring(0, 512).getBytes(StandardCharsets.UTF_8)),
                bufferFactory.wrap(body.substring(512).getBytes(StandardCharsets.UTF_8)))));
        assertEquals(declareLength ? 1025 : -1, exchange.getRequest().getHeaders().getContentLength());

        StepVerifier.create(filter.filter(exchange, ignored -> Mono.error(new AssertionError("Must not execute"))))
                .verifyComplete();

        assertEquals(HttpStatus.CONTENT_TOO_LARGE, exchange.getResponse().getStatusCode());
        assertEquals("request_body_too_large", JSON_MAPPER.readTree(responseBody(exchange)).path("error").asString());
        assertEquals(List.of("request_body_too_large"), invalidReasons);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"ID\":1", "\"Id\":1", "\"iD\":1", "\"id\":1,\"ID\":2"})
    void caseVariantIdIsRejectedBeforeGovernanceEvenWithCanonicalIdPresent(String idFields) {
        Harness harness = new Harness(McpGatewayAuthorizationMode.DISABLED, decision(true, true), false,
                registry("active_tool"));
        ServerWebExchange exchange = harness.exchange("""
                {"jsonrpc":"2.0",%s,"method":"tools/call","params":{"name":"active_tool"}}
                """.formatted(idFields));

        StepVerifier.create(harness.filter.filter(exchange, harness.downstream())).verifyComplete();

        assertProtocolError(exchange, HttpStatus.BAD_REQUEST, "null", -32600, "Invalid Request");
        assertEquals(List.of(), harness.calls);
        assertNull(harness.downstreamBody.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":1}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{}}",
            "{\"jsonrpc\":\"2.0\",\"id\":4,\"error\":{\"code\":-32603,\"message\":\"Internal error\"}}"
    })
    void nonToolCallsAndResponseEnvelopesBypassToolAvailabilityCheck(String body) {
        Harness harness = new Harness(McpGatewayAuthorizationMode.ENFORCE, decision(true, true), true,
                registry());
        ServerWebExchange exchange = harness.exchange(body);

        StepVerifier.create(harness.filter.filter(exchange, harness.downstream())).verifyComplete();

        assertArrayEquals(body.getBytes(StandardCharsets.UTF_8), harness.downstreamBody.get());
        if (JSON_MAPPER.readTree(body).has("method")) {
            assertTrue(harness.calls.contains("protect"));
        } else {
            assertEquals(List.of("downstream"), harness.calls);
        }
    }

    @Test
    void toolListingStillUsesItsOwnAuthorizationWhenRegistryIsConfigured() {
        Harness harness = new Harness(McpGatewayAuthorizationMode.ENFORCE, decision(false, true), true,
                registry());
        ServerWebExchange exchange = harness.exchange("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");

        StepVerifier.create(harness.filter.filter(exchange, harness.downstream())).verifyComplete();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        assertEquals(List.of("principal", "context", "scopes", "authorize", "observe"), harness.calls);
        assertNull(harness.downstreamBody.get());
    }

    @Test
    void registryAloneCanActivateAvailabilityChecksWithoutAuthorizationOrProtectionEvaluators() {
        List<String> calls = new ArrayList<>();
        McpGatewayWebFluxGovernanceFilter filter = registryOnlyFilter(registry("active_tool"));
        ServerWebExchange unknown = basicExchange(toolCall("unknown", "1"));
        ServerWebExchange known = basicExchange(toolCall("active_tool", "2"));
        WebFilterChain downstream = exchange -> {
            calls.add("downstream");
            return exchange.getResponse().setComplete();
        };

        StepVerifier.create(filter.filter(unknown, downstream)).verifyComplete();
        StepVerifier.create(filter.filter(known, downstream)).verifyComplete();

        assertProtocolError(unknown, HttpStatus.OK, "1", -32602, "Unknown tool");
        assertEquals(List.of("downstream"), calls);
    }

    @Test
    void explicitlyConfiguredRegistryStillRejectsWhenBothGovernanceEvaluatorsAreDisabled() {
        Harness harness = new Harness(McpGatewayAuthorizationMode.DISABLED, decision(true, true), false,
                registry());
        ServerWebExchange exchange = harness.exchange(toolCall("unknown", "1"));

        StepVerifier.create(harness.filter.filter(exchange, harness.downstream())).verifyComplete();

        assertProtocolError(exchange, HttpStatus.OK, "1", -32602, "Unknown tool");
        assertEquals(List.of(), harness.calls);
    }

    @Test
    void emptyRegistryDeniesEveryToolRatherThanDisablingTheAvailabilityCheck() {
        McpGatewayWebFluxGovernanceFilter filter = registryOnlyFilter(registry());
        ServerWebExchange exchange = basicExchange(toolCall("active_tool", "1"));

        StepVerifier.create(filter.filter(exchange, ignored -> Mono.error(new AssertionError("Must not execute"))))
                .verifyComplete();

        assertProtocolError(exchange, HttpStatus.OK, "1", -32602, "Unknown tool");
    }

    @Test
    void registrySnapshotIsImmutableAndToolNamesAreCaseSensitive() {
        List<McpToolDescriptor> source = new ArrayList<>(List.of(descriptor("active_tool")));
        McpGatewayWebFluxGovernanceFilter filter = registryOnlyFilter(McpToolRegistry.of(source));
        source.clear();
        source.add(descriptor("added_later"));
        List<String> calls = new ArrayList<>();
        ServerWebExchange active = basicExchange(toolCall("active_tool", "1"));

        StepVerifier.create(filter.filter(active, ignored -> {
            calls.add("downstream");
            return Mono.empty();
        })).verifyComplete();

        for (String name : List.of("ACTIVE_TOOL", "added_later")) {
            ServerWebExchange exchange = basicExchange(toolCall(name, "2"));
            StepVerifier.create(filter.filter(exchange, ignored -> Mono.error(new AssertionError("Must not execute"))))
                    .verifyComplete();
            assertProtocolError(exchange, HttpStatus.OK, "2", -32602, "Unknown tool");
        }
        assertEquals(List.of("downstream"), calls);
    }

    @Test
    void toolRegistryBuilderRejectsExplicitNullInsteadOfSilentlyDisablingChecks() {
        McpGatewayWebFluxGovernanceFilter.Builder builder = McpGatewayWebFluxGovernanceFilter.builder(
                JSON_MAPPER, (authentication, exchange, invocation) ->
                        GatewayToolExecutionContext.of(null, null, null, invocation, null));

        assertThrows(NullPointerException.class, () -> builder.toolRegistry(null));
    }

    @Test
    void accessRegistryToolRegistryWorksDirectlyWithTheRealAuthorizer() {
        McpToolAccessRegistry accessRegistry = McpToolAccessRegistry.of(List.of(
                McpToolAccessRule.of("active_tool", TEST_SURFACE, List.of("demo:run")),
                McpToolAccessRule.of("restricted_tool", TEST_SURFACE, List.of("demo:admin"))
        ));
        McpToolAuthorizer authorizer = McpToolAuthorizer.of(accessRegistry, List.of("demo:list"));
        Harness harness = new Harness(McpGatewayAuthorizationMode.ENFORCE,
                (scopes, context) -> authorizer.authorize(context, scopes, false, true), false,
                accessRegistry.toolRegistry());
        String allowedBody = toolCall("active_tool", "1");
        ServerWebExchange allowed = harness.exchange(allowedBody);
        ServerWebExchange denied = harness.exchange(toolCall("restricted_tool", "2"));
        ServerWebExchange unknown = harness.exchange(toolCall("never_registered", "3"));

        StepVerifier.create(harness.filter.filter(allowed, harness.downstream())).verifyComplete();
        StepVerifier.create(harness.filter.filter(denied, harness.downstream())).verifyComplete();
        StepVerifier.create(harness.filter.filter(unknown, harness.downstream())).verifyComplete();

        assertEquals(HttpStatus.OK, allowed.getResponse().getStatusCode());
        assertArrayEquals(allowedBody.getBytes(StandardCharsets.UTF_8), harness.downstreamBody.get());
        assertEquals(HttpStatus.FORBIDDEN, denied.getResponse().getStatusCode());
        assertEquals("Bearer error=\"insufficient_scope\", scope=\"demo:admin\"",
                denied.getResponse().getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE));
        assertProtocolError(unknown, HttpStatus.OK, "3", -32602, "Unknown tool");
        assertEquals(List.of("principal", "context", "scopes", "authorize", "observe", "downstream",
                "principal", "context", "scopes", "authorize", "observe"), harness.calls);
        assertEquals(List.of("active_tool", "restricted_tool"), harness.authorizationObservations.stream()
                .map(McpAuthorizationObservation::actionName).toList());
    }

    @Test
    void misconfiguredAccessRulesCannotOverrideRegisteredToolAvailability() {
        McpToolAccessRegistry accessRegistry = McpToolAccessRegistry.of(List.of(
                McpToolAccessRule.of("disabled_tool", TEST_SURFACE, List.of("demo:run"))
        ));
        McpToolAuthorizer authorizer = McpToolAuthorizer.of(accessRegistry, List.of("demo:list"));
        Harness harness = new Harness(McpGatewayAuthorizationMode.ENFORCE,
                (scopes, context) -> authorizer.authorize(context, scopes, false, true), true,
                registry("active_tool"));
        ServerWebExchange disabled = harness.exchange(toolCall("disabled_tool", "1"));
        ServerWebExchange misconfigured = harness.exchange(toolCall("active_tool", "2"));

        StepVerifier.create(harness.filter.filter(disabled, harness.downstream())).verifyComplete();
        assertProtocolError(disabled, HttpStatus.OK, "1", -32602, "Unknown tool");
        assertEquals(List.of(), harness.calls);

        StepVerifier.create(harness.filter.filter(misconfigured, harness.downstream())).verifyComplete();

        assertProtocolError(misconfigured, HttpStatus.OK, "2", -32603, "Internal error");
        assertEquals(List.of("principal", "context", "scopes", "authorize"), harness.calls);
        assertEquals(List.of(), harness.authorizationObservations);
        assertNull(harness.downstreamBody.get());
    }

    private static McpGatewayWebFluxGovernanceFilter registryOnlyFilter(McpToolRegistry registry) {
        return McpGatewayWebFluxGovernanceFilter.builder(JSON_MAPPER,
                        (authentication, exchange, invocation) ->
                                GatewayToolExecutionContext.of(null, "workspace", null, invocation, null))
                .toolRegistry(registry)
                .build();
    }

    private static McpGatewayWebFluxGovernanceFilter guardedRegistryOnlyFilter(List<String> invalidReasons) {
        return McpGatewayWebFluxGovernanceFilter.builder(JSON_MAPPER,
                        (authentication, exchange, invocation) -> {
                            throw new AssertionError("Malformed body must not resolve context");
                        })
                .properties(new McpGatewayWebFluxProperties("/mcp", 1024, 0))
                .toolRegistry(registry("active_tool"))
                .invalidRequestObserver((reason, requestId, correlationId) -> invalidReasons.add(reason))
                .build();
    }

    private static void assertProtocolError(ServerWebExchange exchange,
                                            HttpStatus status,
                                            String jsonId,
                                            int errorCode,
                                            String message) {
        assertEquals(status, exchange.getResponse().getStatusCode());
        assertEquals(MediaType.APPLICATION_JSON, exchange.getResponse().getHeaders().getContentType());
        assertFalse(exchange.getResponse().getHeaders().containsHeader(HttpHeaders.WWW_AUTHENTICATE));
        JsonNode expected = JSON_MAPPER.readTree("""
                {"jsonrpc":"2.0","id":%s,"error":{"code":%d,"message":"%s"}}
                """.formatted(jsonId, errorCode, message));
        assertEquals(expected, JSON_MAPPER.readTree(responseBody(exchange)));
    }

    private static McpToolRegistry registry(String... names) {
        return McpToolRegistry.of(List.of(names).stream().map(McpGatewayWebFluxToolRegistryTest::descriptor).toList());
    }

    private static McpToolDescriptor descriptor(String name) {
        return McpToolDescriptor.builder(name, TEST_SURFACE).build();
    }

    private static ToolAuthorizationDecision decision(boolean allowed, boolean mapped) {
        List<String> required = mapped ? List.of(allowed ? "demo:run" : "demo:admin") : List.of();
        return new ToolAuthorizationDecision(allowed, mapped, "active_tool", required, List.of("demo:run"),
                allowed ? List.of() : required);
    }

    private static String toolCall(String name, String jsonId) {
        return """
                {
                  "jsonrpc" : "2.0", "id" : %s,
                  "method" : "tools/call",
                  "params" : { "name" : "%s", "arguments" : { "label" : "雪🌊" } }
                }
                """.formatted(jsonId, name);
    }

    private static ServerWebExchange basicExchange(String body) {
        return MockServerWebExchange.from(MockServerHttpRequest.post("/mcp")
                .contentType(MediaType.APPLICATION_JSON).body(body));
    }

    private static String responseBody(ServerWebExchange exchange) {
        return ((MockServerHttpResponse) exchange.getResponse()).getBodyAsString().block();
    }

    private static final class Harness {
        private final List<String> calls = new ArrayList<>();
        private final List<McpAuthorizationObservation> authorizationObservations = new ArrayList<>();
        private final AtomicReference<byte[]> downstreamBody = new AtomicReference<>();
        private final AtomicReference<McpAbuseProtectionDecision> protectionDecision = new AtomicReference<>();
        private final McpGatewayWebFluxGovernanceFilter filter;

        private Harness(McpGatewayAuthorizationMode mode,
                        ToolAuthorizationDecision authorizationDecision,
                        boolean protectionEnabled,
                        McpToolRegistry registry) {
            this(mode, (scopes, context) -> authorizationDecision, protectionEnabled, registry);
        }

        private Harness(McpGatewayAuthorizationMode mode,
                        BiFunction<Collection<String>, GatewayToolExecutionContext, ToolAuthorizationDecision> authorizer,
                        boolean protectionEnabled,
                        McpToolRegistry registry) {
            filter = McpGatewayWebFluxGovernanceFilter.builder(JSON_MAPPER,
                            (authentication, exchange, invocation) -> {
                                calls.add("context");
                                return GatewayToolExecutionContext.of(authentication.getName(), "workspace",
                                        CORRELATION_ID, invocation, null);
                            })
                    .toolRegistry(registry)
                    .authorization(() -> mode, (scopes, context) -> {
                        calls.add("authorize");
                        return authorizer.apply(scopes, context);
                    })
                    .protection(() -> protectionEnabled, context -> {
                        calls.add("protect");
                        if (protectionDecision.get() != null) {
                            return protectionDecision.get();
                        }
                        return McpAbuseProtectionDecision.allow(context.toolName(), context.principalId(), context.workspaceId());
                    })
                    .grantedScopesExtractor(authentication -> {
                        calls.add("scopes");
                        return List.of("demo:run");
                    })
                    .authorizationObserver(observation -> {
                        calls.add("observe");
                        authorizationObservations.add(observation);
                    })
                    .protectionRejectionObserver((decision, context) -> calls.add("protection-reject"))
                    .correlationIdResolver(exchange -> CORRELATION_ID)
                    .invalidRequestObserver((reason, requestId, correlationId) -> calls.add("invalid:" + reason))
                    .build();
        }

        private ServerWebExchange exchange(String body) {
            return basicExchange(body).mutate()
                    .principal(Mono.defer(() -> {
                        calls.add("principal");
                        return Mono.just(new UsernamePasswordAuthenticationToken("demo-client", "not-used",
                                List.of(new SimpleGrantedAuthority("SCOPE_demo:run"))));
                    }))
                    .build();
        }

        private WebFilterChain downstream() {
            return exchange -> {
                calls.add("downstream");
                exchange.getResponse().setStatusCode(HttpStatus.OK);
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
