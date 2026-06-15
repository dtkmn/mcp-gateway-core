package mcp.gateway.core.governance;

import java.util.Objects;
import mcp.gateway.core.authz.ToolAuthorizationDecision;
import mcp.gateway.core.protection.McpAbuseProtectionDecision;

/**
 * Result of a framework-neutral MCP tool governance pass.
 *
 * @param outcome final outcome
 * @param reason low-cardinality reason
 * @param authorizationDecision authorization decision, when authorization ran
 * @param authorizationObservationOutcome observation outcome for authorization, when applicable
 * @param authorizationObservationReason observation reason for authorization, when applicable
 * @param protectionDecision protection decision, when protection ran
 */
public record GatewayToolGovernanceDecision(
        GatewayToolGovernanceOutcome outcome,
        GatewayToolGovernanceReason reason,
        ToolAuthorizationDecision authorizationDecision,
        GatewayToolGovernanceOutcome authorizationObservationOutcome,
        GatewayToolGovernanceReason authorizationObservationReason,
        McpAbuseProtectionDecision protectionDecision
) {
    /**
     * Creates a normalized decision.
     */
    public GatewayToolGovernanceDecision {
        outcome = Objects.requireNonNull(outcome, "outcome must not be null");
        reason = Objects.requireNonNull(reason, "reason must not be null");
    }

    /**
     * Returns whether the request may proceed to the downstream tool runtime.
     *
     * @return true when not rejected
     */
    public boolean allowed() {
        return outcome != GatewayToolGovernanceOutcome.REJECT;
    }

    /**
     * Returns whether authorization produced an observable decision.
     *
     * @return true when authorization observation fields are populated
     */
    public boolean hasAuthorizationObservation() {
        return authorizationDecision != null && authorizationObservationOutcome != null;
    }

    static GatewayToolGovernanceDecision allow(ToolAuthorizationDecision authorizationDecision,
                                               GatewayToolGovernanceReason authorizationObservationReason,
                                               McpAbuseProtectionDecision protectionDecision) {
        return new GatewayToolGovernanceDecision(
                GatewayToolGovernanceOutcome.ALLOW,
                GatewayToolGovernanceReason.GOVERNANCE_PASSED,
                authorizationDecision,
                authorizationDecision == null ? null : GatewayToolGovernanceOutcome.ALLOW,
                authorizationDecision == null ? null : authorizationObservationReason,
                protectionDecision
        );
    }

    static GatewayToolGovernanceDecision warn(ToolAuthorizationDecision authorizationDecision,
                                              GatewayToolGovernanceReason reason,
                                              McpAbuseProtectionDecision protectionDecision) {
        return new GatewayToolGovernanceDecision(
                GatewayToolGovernanceOutcome.WARN,
                reason,
                authorizationDecision,
                GatewayToolGovernanceOutcome.WARN,
                reason,
                protectionDecision
        );
    }

    static GatewayToolGovernanceDecision rejectAuthorization(ToolAuthorizationDecision authorizationDecision,
                                                            GatewayToolGovernanceReason reason) {
        return new GatewayToolGovernanceDecision(
                GatewayToolGovernanceOutcome.REJECT,
                reason,
                authorizationDecision,
                GatewayToolGovernanceOutcome.REJECT,
                reason,
                null
        );
    }

    static GatewayToolGovernanceDecision rejectProtection(ToolAuthorizationDecision authorizationDecision,
                                                         GatewayToolGovernanceOutcome authorizationObservationOutcome,
                                                         GatewayToolGovernanceReason authorizationObservationReason,
                                                         McpAbuseProtectionDecision protectionDecision) {
        return new GatewayToolGovernanceDecision(
                GatewayToolGovernanceOutcome.REJECT,
                GatewayToolGovernanceReason.PROTECTION_REJECTED,
                authorizationDecision,
                authorizationObservationOutcome,
                authorizationObservationReason,
                protectionDecision
        );
    }
}
