package mcp.gateway.core.governance;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import mcp.gateway.core.authz.ToolAuthorizationDecision;
import mcp.gateway.core.context.GatewayToolExecutionContext;
import mcp.gateway.core.protection.McpAbuseProtectionDecision;

/**
 * Framework-neutral orchestration for the common MCP gateway governance flow:
 * authorize, apply abuse protection, then return one pass/warn/reject decision.
 */
public final class GatewayToolGovernance {
    private GatewayToolGovernance() {
    }

    /**
     * Evaluates authorization followed by protection.
     *
     * @param context normalized tool execution context
     * @param grantedScopes scopes granted to the caller
     * @param authorizationEvaluator authorization step, or null to skip
     * @param protectionEvaluator protection step, or null to skip
     * @return governance decision
     */
    public static GatewayToolGovernanceDecision evaluate(
            GatewayToolExecutionContext context,
            Collection<String> grantedScopes,
            GatewayToolAuthorizationEvaluator authorizationEvaluator,
            GatewayToolProtectionEvaluator protectionEvaluator
    ) {
        Objects.requireNonNull(context, "context must not be null");
        Collection<String> scopes = grantedScopes == null ? List.of() : grantedScopes;

        ToolAuthorizationDecision authorizationDecision = null;
        GatewayToolGovernanceOutcome authorizationObservationOutcome = null;
        GatewayToolGovernanceReason authorizationReason = null;

        if (authorizationEvaluator != null) {
            GatewayToolAuthorizationPolicy policy = Objects.requireNonNull(
                    authorizationEvaluator.policy(),
                    "authorization evaluator policy must not be null"
            );
            if (policy.enabled() && context.invocation().authorizable()) {
                authorizationDecision = authorizationEvaluator.authorize(scopes, context);
                if (authorizationDecision == null) {
                    throw new IllegalStateException("authorization evaluator must not return null");
                }
                authorizationReason = authorizationReason(authorizationDecision);
                authorizationObservationOutcome = authorizationOutcome(authorizationDecision, policy);

                if (authorizationObservationOutcome == GatewayToolGovernanceOutcome.REJECT) {
                    return GatewayToolGovernanceDecision.rejectAuthorization(
                            authorizationDecision,
                            authorizationReason
                    );
                }
            }
        }

        McpAbuseProtectionDecision protectionDecision = null;
        if (protectionEvaluator != null && protectionEvaluator.enabled()) {
            protectionDecision = protectionEvaluator.evaluate(context);
            if (protectionDecision == null) {
                throw new IllegalStateException("protection evaluator must not return null");
            }
            if (!protectionDecision.allowed()) {
                return GatewayToolGovernanceDecision.rejectProtection(
                        authorizationDecision,
                        authorizationObservationOutcome,
                        authorizationReason,
                        protectionDecision
                );
            }
        }

        if (authorizationObservationOutcome == GatewayToolGovernanceOutcome.WARN) {
            return GatewayToolGovernanceDecision.warn(
                    authorizationDecision,
                    authorizationReason,
                    protectionDecision
            );
        }

        return GatewayToolGovernanceDecision.allow(authorizationDecision, authorizationReason, protectionDecision);
    }

    private static GatewayToolGovernanceOutcome authorizationOutcome(ToolAuthorizationDecision decision,
                                                                    GatewayToolAuthorizationPolicy policy) {
        if (!decision.mapped()) {
            return policy.rejectUnmapped()
                    ? GatewayToolGovernanceOutcome.REJECT
                    : GatewayToolGovernanceOutcome.WARN;
        }
        if (!decision.allowed()) {
            return policy.rejectDenied()
                    ? GatewayToolGovernanceOutcome.REJECT
                    : GatewayToolGovernanceOutcome.WARN;
        }
        return GatewayToolGovernanceOutcome.ALLOW;
    }

    private static GatewayToolGovernanceReason authorizationReason(ToolAuthorizationDecision decision) {
        if (!decision.mapped()) {
            return GatewayToolGovernanceReason.UNMAPPED_TOOL;
        }
        if (!decision.allowed()) {
            return GatewayToolGovernanceReason.INSUFFICIENT_SCOPE;
        }
        return GatewayToolGovernanceReason.SCOPE_GRANTED;
    }
}
