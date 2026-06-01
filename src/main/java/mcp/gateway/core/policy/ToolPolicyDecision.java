package mcp.gateway.core.policy;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Generic allow, deny, or abstain decision for an MCP tool policy check.
 *
 * @param outcome policy outcome
 * @param reason human-readable reason for the decision
 * @param details immutable machine-readable details
 */
public record ToolPolicyDecision(
        ToolPolicyOutcome outcome,
        String reason,
        Map<String, Object> details
) {
    /**
     * Creates a normalized policy decision.
     *
     * @param outcome policy outcome
     * @param reason human-readable reason
     * @param details decision details
     */
    public ToolPolicyDecision {
        outcome = outcome == null ? ToolPolicyOutcome.DENY : outcome;
        reason = normalize(reason);
        details = safeDetails(details);
    }

    /**
     * Returns whether this decision allows the tool call.
     *
     * @return {@code true} when the outcome is {@link ToolPolicyOutcome#ALLOW}
     */
    public boolean allowed() {
        return outcome == ToolPolicyOutcome.ALLOW;
    }

    /**
     * Returns whether this decision denies the tool call.
     *
     * @return {@code true} when the outcome is {@link ToolPolicyOutcome#DENY}
     */
    public boolean denied() {
        return outcome == ToolPolicyOutcome.DENY;
    }

    /**
     * Returns whether this decision abstains from deciding.
     *
     * @return {@code true} when the outcome is {@link ToolPolicyOutcome#ABSTAIN}
     */
    public boolean abstained() {
        return outcome == ToolPolicyOutcome.ABSTAIN;
    }

    /**
     * Creates an allow decision.
     *
     * @param reason decision reason
     * @return allow decision
     */
    public static ToolPolicyDecision allow(String reason) {
        return allow(reason, Map.of());
    }

    /**
     * Creates an allow decision.
     *
     * @param reason decision reason
     * @param details decision details
     * @return allow decision
     */
    public static ToolPolicyDecision allow(String reason, Map<String, Object> details) {
        return new ToolPolicyDecision(ToolPolicyOutcome.ALLOW, reason, details);
    }

    /**
     * Creates a deny decision.
     *
     * @param reason decision reason
     * @return deny decision
     */
    public static ToolPolicyDecision deny(String reason) {
        return deny(reason, Map.of());
    }

    /**
     * Creates a deny decision.
     *
     * @param reason decision reason
     * @param details decision details
     * @return deny decision
     */
    public static ToolPolicyDecision deny(String reason, Map<String, Object> details) {
        return new ToolPolicyDecision(ToolPolicyOutcome.DENY, reason, details);
    }

    /**
     * Creates an abstain decision.
     *
     * @param reason decision reason
     * @return abstain decision
     */
    public static ToolPolicyDecision abstain(String reason) {
        return abstain(reason, Map.of());
    }

    /**
     * Creates an abstain decision.
     *
     * @param reason decision reason
     * @param details decision details
     * @return abstain decision
     */
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
