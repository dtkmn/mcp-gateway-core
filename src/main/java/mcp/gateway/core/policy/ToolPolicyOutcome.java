package mcp.gateway.core.policy;

/**
 * Outcome of a tool policy evaluation.
 */
public enum ToolPolicyOutcome {
    /** The tool call is allowed by policy. */
    ALLOW,
    /** The tool call is denied by policy. */
    DENY,
    /** The provider did not make a decision. */
    ABSTAIN
}
