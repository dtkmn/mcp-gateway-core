package mcp.gateway.core.policybundle;

/**
 * Source of the final policy bundle decision.
 */
public enum PolicyBundleDecisionSource {
    /**
     * An enabled rule matched.
     */
    RULE("rule"),

    /**
     * No enabled rule matched, so the bundle default was used.
     */
    DEFAULT("default");

    private final String wireValue;

    PolicyBundleDecisionSource(String wireValue) {
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
}
