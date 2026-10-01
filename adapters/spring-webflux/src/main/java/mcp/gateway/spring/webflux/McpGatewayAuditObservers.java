package mcp.gateway.spring.webflux;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import mcp.gateway.core.audit.GatewayAuditEvent;
import mcp.gateway.core.audit.GatewayAuditSink;
import mcp.gateway.core.context.GatewayToolExecutionContext;
import mcp.gateway.core.protection.McpAbuseProtectionDecision;

/**
 * Opt-in translation of WebFlux governance observations into audit events.
 * Use one instance with the existing authorization, protection-rejection,
 * invalid-request, and adapter-rejection observer builder settings.
 * <p>
 * Events describe pre-execution governance, not tool completion. Only the
 * documented fields are copied; request bodies, arguments, headers, and context
 * targets are not included. Optional null fields are omitted, and sparse
 * diagnostics do not invent a principal, workspace, or tool.
 * <p>
 * Generated details contain only immutable strings, a retry-delay number, and
 * the authorization observation's immutable scope lists. The event copies and
 * freezes the outer map; no arbitrary application metadata is accepted.
 * Each emitted event makes one synchronous sink publication
 * attempt, without retry or exception suppression. Sink failures
 * propagate to the caller; when installed in the filter, they propagate through
 * its publisher, prevent downstream execution, and may prevent the normal
 * rejection response. Storage, redaction, retention, and delivery policy belong
 * to the sink's owner.
 */
public final class McpGatewayAuditObservers implements McpAuthorizationObserver,
        McpProtectionRejectionObserver, McpInvalidRequestObserver, McpAdapterRejectionObserver {
    private final GatewayAuditSink sink;

    private McpGatewayAuditObservers(GatewayAuditSink sink) {
        this.sink = Objects.requireNonNull(sink, "sink must not be null");
    }

    /**
     * Creates observers backed by a runtime-owned sink. Creation does not
     * register observers or change any filter behavior.
     *
     * @param sink audit sink
     * @return observer bundle for the existing builder settings
     * @throws NullPointerException if the sink is null
     */
    public static McpGatewayAuditObservers of(GatewayAuditSink sink) {
        return new McpGatewayAuditObservers(sink);
    }

    /**
     * Emits an {@code authorization} event with the observation's outcome
     * ({@code allowed}, {@code denied}, or {@code warn}). Its principal comes
     * from the context when available. Details contain {@code action},
     * {@code reason}, {@code requiredScopes}, {@code grantedScopes}, and the
     * context's {@code workspaceId} and {@code correlationId} when available.
     * Scope lists retain the observation's values and order.
     *
     * @param observation non-null authorization observation
     * @throws NullPointerException if the observation is null
     */
    @Override
    public void record(McpAuthorizationObservation observation) {
        Objects.requireNonNull(observation, "observation must not be null");
        Map<String, Object> details = new LinkedHashMap<>();
        putIfPresent(details, "action", observation.actionName());
        putIfPresent(details, "reason", observation.reason());
        details.put("requiredScopes", observation.requiredScopes());
        details.put("grantedScopes", observation.grantedScopes());
        GatewayToolExecutionContext context = observation.context();
        if (context != null) {
            putIfPresent(details, "workspaceId", context.workspaceId());
            putIfPresent(details, "correlationId", context.correlationId());
        }
        publish("authorization", context == null ? null : context.principalId(), observation.outcome(), details);
    }

    /**
     * Emits a {@code protection_rejection} event with outcome {@code rejected}
     * and the decision's client as principal. Details contain the decision's
     * {@code tool}, {@code errorCode}, {@code reason}, {@code retryAfterSeconds},
     * and {@code workspaceId}, plus {@code correlationId} from the optional
     * context. An allowed decision emits nothing.
     *
     * @param decision non-null protection decision
     * @param context optional context used only for correlation
     * @throws NullPointerException if the decision is null
     */
    @Override
    public void rejected(McpAbuseProtectionDecision decision, GatewayToolExecutionContext context) {
        Objects.requireNonNull(decision, "decision must not be null");
        if (decision.allowed()) {
            return;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        putIfPresent(details, "tool", decision.toolName());
        putIfPresent(details, "errorCode", decision.errorCode());
        putIfPresent(details, "reason", decision.reason());
        details.put("retryAfterSeconds", decision.retryAfterSeconds());
        putIfPresent(details, "workspaceId", decision.workspaceId());
        if (context != null) {
            putIfPresent(details, "correlationId", context.correlationId());
        }
        publish("protection_rejection", decision.clientId(), "rejected", details);
    }

    /**
     * Emits an {@code invalid_mcp_request} event with outcome {@code rejected}
     * and no principal. Details contain only the available {@code reason},
     * server {@code requestId}, and {@code correlationId}.
     *
     * @param reason optional invalid-request reason
     * @param requestId optional server HTTP request id, not the JSON-RPC id
     * @param correlationId optional correlation id
     */
    @Override
    public void rejected(String reason, String requestId, String correlationId) {
        publish("invalid_mcp_request", null, "rejected", diagnosticDetails(reason, requestId, correlationId));
    }

    /**
     * Emits an {@code adapter_rejection} event with outcome {@code rejected}
     * and no principal. Details contain only {@code reason} from
     * {@link McpAdapterRejectionReason#code()}, plus the available server
     * {@code requestId} and {@code correlationId}. This outcome also describes
     * an ID-less tool call acknowledged with HTTP 202 without execution; it
     * does not represent an HTTP error or a permission denial in every case.
     *
     * @param reason non-null adapter rejection reason
     * @param requestId optional server HTTP request id, not the JSON-RPC id
     * @param correlationId optional correlation id
     * @throws NullPointerException if the reason is null
     */
    @Override
    public void rejected(McpAdapterRejectionReason reason, String requestId, String correlationId) {
        Objects.requireNonNull(reason, "reason must not be null");
        publish("adapter_rejection", null, "rejected", diagnosticDetails(reason.code(), requestId, correlationId));
    }

    private Map<String, Object> diagnosticDetails(String reason, String requestId, String correlationId) {
        Map<String, Object> details = new LinkedHashMap<>();
        putIfPresent(details, "reason", reason);
        putIfPresent(details, "requestId", requestId);
        putIfPresent(details, "correlationId", correlationId);
        return details;
    }

    private void publish(String type, String principal, String outcome, Map<String, Object> details) {
        sink.publish(GatewayAuditEvent.of(type, principal, outcome, details));
    }

    private static void putIfPresent(Map<String, Object> details, String key, Object value) {
        if (value != null) {
            details.put(key, value);
        }
    }
}
