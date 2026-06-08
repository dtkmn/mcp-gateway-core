package mcp.gateway.core.audit;

import java.util.Map;

/**
 * Sink contract for audit events emitted by an MCP gateway runtime.
 * Implementations own storage, streaming, logging, and delivery behavior.
 */
@FunctionalInterface
public interface GatewayAuditSink {
    /**
     * Publishes one audit event.
     *
     * @param event non-null audit event
     */
    void publish(GatewayAuditEvent event);

    /**
     * Creates and publishes one audit event.
     *
     * @param type event type
     * @param principal authenticated actor or client identifier
     * @param outcome event outcome
     * @param details event details
     */
    default void publish(String type, String principal, String outcome, Map<String, Object> details) {
        publish(GatewayAuditEvent.of(type, principal, outcome, details));
    }

    /**
     * Returns a sink that drops all audit events.
     *
     * @return no-op audit sink
     */
    static GatewayAuditSink noop() {
        return event -> {
        };
    }
}
