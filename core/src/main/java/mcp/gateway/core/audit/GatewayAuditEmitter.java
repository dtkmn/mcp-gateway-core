package mcp.gateway.core.audit;

import java.util.Map;
import java.util.Objects;

/**
 * Small audit emitter that normalizes event creation before delegating to a
 * runtime-owned sink.
 */
public final class GatewayAuditEmitter {
    private final GatewayAuditSink sink;

    private GatewayAuditEmitter(GatewayAuditSink sink) {
        this.sink = Objects.requireNonNull(sink, "sink must not be null");
    }

    /**
     * Creates an emitter for a runtime-owned sink.
     *
     * @param sink audit sink
     * @return audit emitter
     */
    public static GatewayAuditEmitter of(GatewayAuditSink sink) {
        return new GatewayAuditEmitter(sink);
    }

    /**
     * Emits an audit event.
     *
     * @param event audit event, or null to emit an unknown-event fallback
     */
    public void emit(GatewayAuditEvent event) {
        sink.publish(event == null ? GatewayAuditEvent.of(null, null, null, Map.of()) : event);
    }

    /**
     * Creates and emits an audit event.
     *
     * @param type event type
     * @param principal authenticated actor or client identifier
     * @param outcome event outcome
     * @param details event details
     */
    public void emit(String type, String principal, String outcome, Map<String, Object> details) {
        emit(GatewayAuditEvent.of(type, principal, outcome, details));
    }
}
