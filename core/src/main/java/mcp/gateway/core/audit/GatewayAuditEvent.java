package mcp.gateway.core.audit;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import mcp.gateway.core.metadata.GatewayMetadataSnapshot;

/**
 * Generic audit event emitted by an MCP gateway runtime.
 * <p>
 * Details are copied into an unmodifiable outer map, dropping null keys and
 * values. Nested values retain their original references. For supported nested
 * metadata snapshots, pass {@link GatewayMetadataSnapshot#copyOf(Map)} as details.
 *
 * @param type event type
 * @param principal authenticated actor or client identifier
 * @param outcome normalized event outcome
 * @param details event details with an unmodifiable outer map
 */
public record GatewayAuditEvent(
        String type,
        String principal,
        String outcome,
        Map<String, Object> details
) {
    /**
     * Creates a normalized audit event.
     *
     * @param type event type
     * @param principal authenticated actor or client identifier
     * @param outcome event outcome
     * @param details event details
     */
    public GatewayAuditEvent {
        type = normalize(type);
        principal = normalize(principal);
        outcome = normalize(outcome);
        details = safeDetails(details);
    }

    /**
     * Creates a normalized audit event.
     *
     * @param type event type
     * @param principal authenticated actor or client identifier
     * @param outcome event outcome
     * @param details event details
     * @return normalized audit event
     */
    public static GatewayAuditEvent of(String type,
                                       String principal,
                                       String outcome,
                                       Map<String, Object> details) {
        return new GatewayAuditEvent(type, principal, outcome, details);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static Map<String, Object> safeDetails(Map<String, Object> details) {
        if (details == null || details.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        details.forEach((key, value) -> {
            if (key != null && value != null) {
                copy.put(key, value);
            }
        });
        if (copy.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(copy);
    }
}
