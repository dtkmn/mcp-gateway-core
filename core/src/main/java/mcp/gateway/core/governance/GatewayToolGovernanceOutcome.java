package mcp.gateway.core.governance;

/**
 * Final outcome of a gateway governance pass.
 */
public enum GatewayToolGovernanceOutcome {
    /** Request can proceed without a warning decision. */
    ALLOW,
    /** Request can proceed, but a governance decision should be observed. */
    WARN,
    /** Request must be rejected before tool execution. */
    REJECT
}
