package mcp.gateway.spring.webflux;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import mcp.gateway.core.audit.GatewayAuditEvent;
import mcp.gateway.core.authz.McpToolAccessRegistry;
import mcp.gateway.core.authz.McpToolAccessRule;
import mcp.gateway.core.authz.McpToolAuthorizer;
import mcp.gateway.core.context.GatewayToolExecutionContext;
import mcp.gateway.core.invocation.McpToolInvocation;
import mcp.gateway.core.protection.McpAbuseProtectionDecision;
import mcp.gateway.core.tool.McpToolDescriptor;
import mcp.gateway.core.tool.McpToolRegistry;
import mcp.gateway.core.tool.McpToolSurface;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class McpGatewayAuditObserversTest {
    private static final String TOOL = "read_records";
    private static final String SCOPE = "records:read";
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    @ParameterizedTest
    @ValueSource(strings = {"allowed", "denied", "warn"})
    void authorizationUsesFixedSchemaAndImmutableScopeLists(String outcome) {
        List<GatewayAuditEvent> events = new ArrayList<>();
        List<String> required = new ArrayList<>(List.of(SCOPE));
        List<String> granted = new ArrayList<>(List.of(SCOPE, "records:write"));
        McpAuthorizationObservation observation = new McpAuthorizationObservation(
                TOOL, outcome, "scope_reason", required, granted, context());

        McpGatewayAuditObservers.of(events::add).record(observation);
        required.clear();
        granted.add("later:scope");

        assertEquals(1, events.size());
        GatewayAuditEvent event = events.get(0);
        assertEquals("authorization", event.type());
        assertEquals(outcome, event.outcome());
        assertEquals("trusted-client", event.principal());
        assertEquals(Map.of(
                "action", TOOL,
                "reason", "scope_reason",
                "requiredScopes", List.of(SCOPE),
                "grantedScopes", List.of(SCOPE, "records:write"),
                "workspaceId", "workspace-a",
                "correlationId", "resolved-correlation"
        ), event.details());
        assertThrows(UnsupportedOperationException.class, () -> event.details().put("extra", "value"));
        assertThrows(UnsupportedOperationException.class,
                () -> ((List<?>) event.details().get("requiredScopes")).clear());
        assertThrows(UnsupportedOperationException.class,
                () -> ((List<?>) event.details().get("grantedScopes")).clear());
        assertFalse(event.toString().contains("TARGET_SECRET"));
    }

    @Test
    void missingAuthorizationContextAndFieldsDoNotInventIdentity() {
        List<GatewayAuditEvent> events = new ArrayList<>();
        McpGatewayAuditObservers.of(events::add).record(
                new McpAuthorizationObservation(null, "denied", null, null, null, null));

        GatewayAuditEvent event = events.get(0);
        assertNull(event.principal());
        assertEquals(Map.of("requiredScopes", List.of(), "grantedScopes", List.of()), event.details());
    }

    @Test
    void protectionUsesDecisionIdentityAndOnlyContextCorrelation() {
        List<GatewayAuditEvent> events = new ArrayList<>();
        McpAbuseProtectionDecision decision = McpAbuseProtectionDecision.reject(
                "rate_limited", "client_rate", "other_tool", "decision-client", "decision-workspace", 7);

        McpGatewayAuditObservers.of(events::add).rejected(decision, context());

        GatewayAuditEvent event = events.get(0);
        assertEquals("protection_rejection", event.type());
        assertEquals("rejected", event.outcome());
        assertEquals("decision-client", event.principal());
        assertEquals(Map.of(
                "tool", "other_tool",
                "errorCode", "rate_limited",
                "reason", "client_rate",
                "retryAfterSeconds", 7L,
                "workspaceId", "decision-workspace",
                "correlationId", "resolved-correlation"
        ), event.details());
    }

    @Test
    void protectionWithoutOptionalFieldsUsesOnlyDecisionInformation() {
        List<GatewayAuditEvent> events = new ArrayList<>();
        McpGatewayAuditObservers.of(events::add).rejected(
                McpAbuseProtectionDecision.reject("overloaded", "capacity", null, null, null, 4), null);

        assertNull(events.get(0).principal());
        assertEquals(Map.of("errorCode", "overloaded", "reason", "capacity", "retryAfterSeconds", 4L),
                events.get(0).details());
    }

    @Test
    void directlyObservedAllowedProtectionDecisionPublishesNothing() {
        AtomicInteger sinkCalls = new AtomicInteger();
        McpGatewayAuditObservers.of(event -> sinkCalls.incrementAndGet())
                .rejected(McpAbuseProtectionDecision.allow(TOOL, "client", "workspace"), context());
        assertEquals(0, sinkCalls.get());
    }

    @Test
    void invalidRequestHasOnlyAvailableDiagnosticFields() {
        List<GatewayAuditEvent> events = new ArrayList<>();
        McpGatewayAuditObservers observers = McpGatewayAuditObservers.of(events::add);
        observers.rejected("invalid_request_shape", "server-request", "correlation");
        observers.rejected((String) null, null, null);

        assertEquals(2, events.size());
        assertEquals("invalid_mcp_request", events.get(0).type());
        assertEquals("rejected", events.get(0).outcome());
        assertNull(events.get(0).principal());
        assertEquals(Map.of("reason", "invalid_request_shape", "requestId", "server-request",
                "correlationId", "correlation"), events.get(0).details());
        assertNull(events.get(1).principal());
        assertEquals(Map.of(), events.get(1).details());
    }

    @ParameterizedTest
    @EnumSource(McpAdapterRejectionReason.class)
    void typedAdapterDiagnosticsUseStableCodesWithoutInventedFields(McpAdapterRejectionReason reason) {
        List<GatewayAuditEvent> events = new ArrayList<>();
        McpGatewayAuditObservers.of(events::add).rejected(reason, null, null);

        assertEquals(1, events.size());
        assertEquals("adapter_rejection", events.get(0).type());
        assertEquals("rejected", events.get(0).outcome());
        assertNull(events.get(0).principal());
        assertEquals(Map.of("reason", reason.code()), events.get(0).details());
    }

    @Test
    void requiredInputsRejectNullBeforePublishing() {
        AtomicInteger sinkCalls = new AtomicInteger();
        McpGatewayAuditObservers observers = McpGatewayAuditObservers.of(event -> sinkCalls.incrementAndGet());

        assertThrows(NullPointerException.class, () -> McpGatewayAuditObservers.of(null));
        assertThrows(NullPointerException.class, () -> observers.record(null));
        assertThrows(NullPointerException.class,
                () -> observers.rejected((McpAbuseProtectionDecision) null, null));
        assertThrows(NullPointerException.class,
                () -> observers.rejected((McpAdapterRejectionReason) null, null, null));
        assertEquals(0, sinkCalls.get());
    }

    @Test
    void largeAlreadyImmutableScopeListsDoNotAcquireGenericSnapshotLimits() {
        List<GatewayAuditEvent> events = new ArrayList<>();
        List<String> scopes = Collections.nCopies(10_001, SCOPE);
        McpGatewayAuditObservers.of(events::add).record(
                new McpAuthorizationObservation(TOOL, "allowed", "scope_granted", scopes, scopes, context()));

        assertEquals(scopes, events.get(0).details().get("requiredScopes"));
        assertEquals(scopes, events.get(0).details().get("grantedScopes"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"authorization", "protection", "invalid", "adapter"})
    void eachCallbackPropagatesSinkFailureAfterOnePublicationAttempt(String callback) {
        AtomicInteger attempts = new AtomicInteger();
        IllegalStateException failure = new IllegalStateException("sink unavailable");
        McpGatewayAuditObservers observers = McpGatewayAuditObservers.of(event -> {
            attempts.incrementAndGet();
            throw failure;
        });

        RuntimeException actual = assertThrows(IllegalStateException.class, () -> {
            switch (callback) {
                case "authorization" -> observers.record(
                        new McpAuthorizationObservation(TOOL, "allowed", "scope_granted",
                                List.of(SCOPE), List.of(SCOPE), context()));
                case "protection" -> observers.rejected(protectionRejection(), context());
                case "invalid" -> observers.rejected("invalid_request_shape", "request", "correlation");
                case "adapter" -> observers.rejected(McpAdapterRejectionReason.UNKNOWN_TOOL, "request", "correlation");
                default -> throw new AssertionError("Unexpected callback: " + callback);
            }
        });

        assertSame(failure, actual);
        assertEquals(1, attempts.get());
    }

    @ParameterizedTest
    @EnumSource(FilterCase.class)
    void existingBuilderCallbacksPublishExpectedEventsWithoutPayloadsOrDuplicateRejections(FilterCase scenario) {
        Harness harness = new Harness(scenario, true, false, null);
        ServerWebExchange exchange = harness.exchange();

        StepVerifier.create(harness.filter.filter(exchange, harness.downstream())).verifyComplete();

        assertFilterResult(scenario, harness, exchange);
        assertEquals(scenario.eventTypes(), harness.events.stream().map(GatewayAuditEvent::type).toList());
        assertEquals(harness.events.size(), harness.sinkAttempts.get());
        if (!harness.events.isEmpty()) {
            GatewayAuditEvent last = harness.events.get(harness.events.size() - 1);
            assertEquals(scenario.reason(), last.details().get("reason"));
            if (last.type().equals("adapter_rejection") || last.type().equals("invalid_mcp_request")) {
                assertNull(last.principal());
                assertEquals(Map.of("reason", scenario.reason(), "requestId", exchange.getRequest().getId(),
                        "correlationId", scenario == FilterCase.UNMAPPED_TOOL
                                ? "resolved-correlation" : "fallback-correlation"), last.details());
            }
            if (scenario == FilterCase.ALLOW || scenario == FilterCase.DENY || scenario == FilterCase.WARN) {
                assertEquals(scenario == FilterCase.ALLOW ? "allowed"
                        : scenario == FilterCase.WARN ? "warn" : "denied", last.outcome());
            }
        }
        for (GatewayAuditEvent event : harness.events) {
            assertFalse(event.toString().contains("BODY_SECRET"));
            assertFalse(event.toString().contains("HEADER_SECRET"));
            assertFalse(event.toString().contains("TARGET_SECRET"));
        }
    }

    @ParameterizedTest
    @EnumSource(value = FilterCase.class, names = {
            "ALLOW", "DENY", "PROTECTION", "INVALID_BODY", "UNKNOWN_TOOL", "INVALID_CONTEXT", "INACTIVE"
    })
    void omittingTheBridgeDoesNotPublishOrChangeFilterResults(FilterCase scenario) {
        Harness harness = new Harness(scenario, false, false, null);
        ServerWebExchange exchange = harness.exchange();

        StepVerifier.create(harness.filter.filter(exchange, harness.downstream())).verifyComplete();

        assertFilterResult(scenario, harness, exchange);
        assertEquals(List.of(), harness.events);
        assertEquals(0, harness.sinkAttempts.get());
    }

    @Test
    void explicitMetricsOnlyCallbackRunsBeforeOneAuditPublicationAndDispatch() {
        Harness harness = new Harness(FilterCase.ALLOW, true, true, null);

        StepVerifier.create(harness.filter.filter(harness.exchange(), harness.downstream())).verifyComplete();

        assertEquals(List.of("metrics", "audit:authorization", "downstream"), harness.calls);
        assertEquals(1, harness.events.size());
    }

    @Test
    void failingSinkIsAttemptedOnceAfterMetricsAndStopsOtherwiseAllowedDispatch() {
        IllegalStateException failure = new IllegalStateException("sink unavailable");
        Harness harness = new Harness(FilterCase.ALLOW, true, true, failure);

        StepVerifier.create(harness.filter.filter(harness.exchange(), harness.downstream()))
                .expectErrorSatisfies(actual -> assertSame(failure, actual))
                .verify();

        assertEquals(List.of("metrics", "audit:authorization"), harness.calls);
        assertEquals(1, harness.sinkAttempts.get());
        assertEquals(List.of(), harness.events);
        assertEquals(0, harness.downstreamCalls.get());
    }

    private static void assertFilterResult(FilterCase scenario, Harness harness, ServerWebExchange exchange) {
        assertEquals(scenario.status(), exchange.getResponse().getStatusCode());
        assertEquals(scenario.executes() ? 1 : 0, harness.downstreamCalls.get());
        if (scenario.executes()) {
            assertEquals(scenario.body(), harness.downstreamBody.get());
        } else {
            assertNull(harness.downstreamBody.get());
        }
        if (scenario == FilterCase.IDLESS_TOOL) {
            assertEquals("", ((MockServerHttpResponse) exchange.getResponse()).getBodyAsString().block());
        }
    }

    private static GatewayToolExecutionContext context() {
        return GatewayToolExecutionContext.of("trusted-client", "workspace-a", "resolved-correlation",
                McpToolInvocation.fromJsonRpc("tools/call", TOOL), "TARGET_SECRET");
    }

    private static McpAbuseProtectionDecision protectionRejection() {
        return McpAbuseProtectionDecision.reject("rate_limited", "client_rate", TOOL,
                "trusted-client", "workspace-a", 7);
    }

    private enum FilterCase {
        ALLOW, DENY, WARN, PROTECTION, INVALID_BODY, OVERSIZED_BODY, UNKNOWN_TOOL,
        IDLESS_TOOL, INVALID_ID, UNMAPPED_TOOL, INVALID_CONTEXT, NULL_CONTEXT, RESPONSE, INACTIVE, OTHER_ROUTE;

        private String body() {
            return switch (this) {
                case INVALID_BODY -> "{\"secret\":\"BODY_SECRET\"";
                case OVERSIZED_BODY -> "BODY_SECRET".repeat(200);
                case RESPONSE -> "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}";
                case INACTIVE -> "BODY_SECRET: unparsed inactive body";
                default -> "{\"jsonrpc\":\"2.0\","
                        + (this == IDLESS_TOOL ? "" : "\"id\":" + (this == INVALID_ID ? "true" : "1") + ",")
                        + "\"method\":\"tools/call\",\"params\":{\"name\":\""
                        + (this == UNKNOWN_TOOL ? "unknown_tool" : this == UNMAPPED_TOOL ? "unmapped_tool" : TOOL)
                        + "\",\"arguments\":{\"secret\":\"BODY_SECRET\"}}}";
            };
        }

        private HttpStatus status() {
            return switch (this) {
                case DENY -> HttpStatus.FORBIDDEN;
                case PROTECTION -> HttpStatus.TOO_MANY_REQUESTS;
                case INVALID_BODY, INVALID_ID -> HttpStatus.BAD_REQUEST;
                case OVERSIZED_BODY -> HttpStatus.CONTENT_TOO_LARGE;
                case IDLESS_TOOL -> HttpStatus.ACCEPTED;
                case INVALID_CONTEXT, NULL_CONTEXT -> HttpStatus.INTERNAL_SERVER_ERROR;
                default -> HttpStatus.OK;
            };
        }

        private boolean executes() {
            return this == ALLOW || this == WARN || this == RESPONSE || this == INACTIVE || this == OTHER_ROUTE;
        }

        private List<String> eventTypes() {
            return switch (this) {
                case ALLOW, DENY, WARN -> List.of("authorization");
                case PROTECTION -> List.of("authorization", "protection_rejection");
                case INVALID_BODY, OVERSIZED_BODY -> List.of("invalid_mcp_request");
                case UNKNOWN_TOOL, IDLESS_TOOL, INVALID_ID, UNMAPPED_TOOL, INVALID_CONTEXT, NULL_CONTEXT ->
                        List.of("adapter_rejection");
                default -> List.of();
            };
        }

        private String reason() {
            return switch (this) {
                case ALLOW -> "scope_granted";
                case DENY, WARN -> "insufficient_scope";
                case PROTECTION -> "client_rate";
                case INVALID_BODY -> "invalid_json_rpc_request";
                case OVERSIZED_BODY -> "request_body_too_large";
                case UNKNOWN_TOOL -> "unknown_tool";
                case IDLESS_TOOL -> "tool_call_without_id";
                case INVALID_ID -> "invalid_tool_call_id";
                case UNMAPPED_TOOL -> "unmapped_tool";
                case INVALID_CONTEXT, NULL_CONTEXT -> "invalid_execution_context";
                default -> null;
            };
        }
    }

    /** Exercises the real adapter with existing core authorization, not a server transport. */
    private static final class Harness {
        private final FilterCase scenario;
        private final List<GatewayAuditEvent> events = new ArrayList<>();
        private final List<String> calls = new ArrayList<>();
        private final AtomicInteger sinkAttempts = new AtomicInteger();
        private final AtomicInteger downstreamCalls = new AtomicInteger();
        private final AtomicReference<String> downstreamBody = new AtomicReference<>();
        private final McpGatewayWebFluxGovernanceFilter filter;

        private Harness(FilterCase scenario, boolean installBridge, boolean metricsFirst, RuntimeException sinkFailure) {
            this.scenario = scenario;
            McpToolAccessRegistry access = McpToolAccessRegistry.of(List.of(
                    McpToolAccessRule.of(TOOL, McpToolSurface.GUIDED, List.of(SCOPE))));
            McpToolAuthorizer authorizer = McpToolAuthorizer.of(access, List.of("mcp:tools:list"));
            McpGatewayWebFluxGovernanceFilter.Builder builder = McpGatewayWebFluxGovernanceFilter.builder(
                            JSON_MAPPER, (authentication, exchange, invocation) -> {
                                if (scenario == FilterCase.NULL_CONTEXT) {
                                    return null;
                                }
                                McpToolInvocation resolvedInvocation = scenario == FilterCase.INVALID_CONTEXT
                                        ? McpToolInvocation.fromJsonRpc("tools/call", "other_tool") : invocation;
                                return GatewayToolExecutionContext.of("trusted-client", "workspace-a",
                                        "resolved-correlation", resolvedInvocation, "TARGET_SECRET");
                            })
                    .properties(new McpGatewayWebFluxProperties("/mcp", 1024, 0))
                    .authorization(() -> scenario == FilterCase.INACTIVE ? McpGatewayAuthorizationMode.DISABLED
                            : scenario == FilterCase.WARN ? McpGatewayAuthorizationMode.WARN
                            : McpGatewayAuthorizationMode.ENFORCE, authorizer::authorize)
                    .grantedScopesExtractor(authentication -> scenario == FilterCase.DENY || scenario == FilterCase.WARN
                            ? List.of() : List.of(SCOPE))
                    .protection(() -> scenario == FilterCase.PROTECTION, ignored -> protectionRejection())
                    .correlationIdResolver(exchange -> "fallback-correlation");
            if (scenario != FilterCase.INACTIVE) {
                builder.toolRegistry(McpToolRegistry.of(List.of(
                        McpToolDescriptor.builder(TOOL, McpToolSurface.GUIDED).build(),
                        McpToolDescriptor.builder("unmapped_tool", McpToolSurface.GUIDED).build())));
            }
            McpGatewayAuditObservers audit = McpGatewayAuditObservers.of(event -> {
                sinkAttempts.incrementAndGet();
                calls.add("audit:" + event.type());
                if (sinkFailure != null) {
                    throw sinkFailure;
                }
                events.add(event);
            });
            if (installBridge) {
                if (metricsFirst) {
                    builder.authorizationObserver(observation -> {
                        calls.add("metrics");
                        audit.record(observation);
                    });
                } else {
                    builder.authorizationObserver(audit);
                }
                builder.protectionRejectionObserver(audit)
                        .invalidRequestObserver(audit)
                        .adapterRejectionObserver(audit);
            }
            filter = builder.build();
        }

        private ServerWebExchange exchange() {
            return MockServerWebExchange.from(MockServerHttpRequest.post(
                            scenario == FilterCase.OTHER_ROUTE ? "/other" : "/mcp")
                    .header("X-Secret", "HEADER_SECRET")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(scenario.body()));
        }

        private WebFilterChain downstream() {
            return exchange -> {
                downstreamCalls.incrementAndGet();
                calls.add("downstream");
                exchange.getResponse().setStatusCode(HttpStatus.OK);
                return DataBufferUtils.join(exchange.getRequest().getBody())
                        .doOnNext(buffer -> {
                            try {
                                byte[] body = new byte[buffer.readableByteCount()];
                                buffer.read(body);
                                downstreamBody.set(new String(body, StandardCharsets.UTF_8));
                            } finally {
                                DataBufferUtils.release(buffer);
                            }
                        })
                        .then(exchange.getResponse().setComplete());
            };
        }
    }
}
