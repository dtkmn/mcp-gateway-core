package mcp.gateway.core.policy;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Generic allow, deny, or abstain decision for an MCP tool policy check.
 */
public record ToolPolicyDecision(
        ToolPolicyOutcome outcome,
        String reason,
        Map<String, Object> details
) {
    public ToolPolicyDecision {
        outcome = outcome == null ? ToolPolicyOutcome.DENY : outcome;
        reason = normalize(reason);
        details = safeDetails(details);
    }

    public boolean allowed() {
        return outcome == ToolPolicyOutcome.ALLOW;
    }

    public boolean denied() {
        return outcome == ToolPolicyOutcome.DENY;
    }

    public boolean abstained() {
        return outcome == ToolPolicyOutcome.ABSTAIN;
    }

    public static ToolPolicyDecision allow(String reason) {
        return allow(reason, Map.of());
    }

    public static ToolPolicyDecision allow(String reason, Map<String, Object> details) {
        return new ToolPolicyDecision(ToolPolicyOutcome.ALLOW, reason, details);
    }

    public static ToolPolicyDecision deny(String reason) {
        return deny(reason, Map.of());
    }

    public static ToolPolicyDecision deny(String reason, Map<String, Object> details) {
        return new ToolPolicyDecision(ToolPolicyOutcome.DENY, reason, details);
    }

    public static ToolPolicyDecision abstain(String reason) {
        return abstain(reason, Map.of());
    }

    public static ToolPolicyDecision abstain(String reason, Map<String, Object> details) {
        return new ToolPolicyDecision(ToolPolicyOutcome.ABSTAIN, reason, details);
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
