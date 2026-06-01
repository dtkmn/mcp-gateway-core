package mcp.gateway.core.audit;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Generic audit event emitted by an MCP gateway runtime.
 */
public record GatewayAuditEvent(
        String type,
        String principal,
        String outcome,
        Map<String, Object> details
) {
    public GatewayAuditEvent {
        type = normalize(type);
        principal = normalize(principal);
        outcome = normalize(outcome);
        details = safeDetails(details);
    }

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
