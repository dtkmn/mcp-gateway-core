package mcp.gateway.core.governance;

/**
 * Low-cardinality reason for a gateway governance decision.
 */
public enum GatewayToolGovernanceReason {
    /** Authorization allowed the request. */
    SCOPE_GRANTED("scope_granted"),
    /** The tool/action was authorizable but not mapped in the access registry. */
    UNMAPPED_TOOL("unmapped_tool"),
    /** The caller lacked one or more required scopes. */
    INSUFFICIENT_SCOPE("insufficient_scope"),
    /** Protection rejected the request. */
    PROTECTION_REJECTED("protection_rejected"),
    /** No active governance step blocked the request. */
    GOVERNANCE_PASSED("governance_passed");

    private final String code;

    GatewayToolGovernanceReason(String code) {
        this.code = code;
    }

    /**
     * Stable machine-readable reason code.
     *
     * @return reason code
     */
    public String code() {
        return code;
    }
}
