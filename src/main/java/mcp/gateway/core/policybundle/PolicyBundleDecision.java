package mcp.gateway.core.policybundle;

import java.util.Locale;

/**
 * Rule/default decision for first-match policy bundle evaluation.
 */
public enum PolicyBundleDecision {
    /**
     * Permit the matched request.
     */
    ALLOW("allow"),

    /**
     * Deny the matched request.
     */
    DENY("deny");

    private final String wireValue;

    PolicyBundleDecision(String wireValue) {
        this.wireValue = wireValue;
    }

    /**
     * Returns the lower-case value used by policy bundle contracts.
     *
     * @return wire value
     */
    public String wireValue() {
        return wireValue;
    }

    /**
     * Parses a policy decision.
     *
     * @param value raw value
     * @return decision
     */
    public static PolicyBundleDecision fromWireValue(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("policy decision must not be blank");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (PolicyBundleDecision decision : values()) {
            if (decision.wireValue.equals(normalized)) {
                return decision;
            }
        }
        throw new IllegalArgumentException("unsupported policy decision: " + value);
    }
}
