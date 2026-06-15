package mcp.gateway.core.governance;

import mcp.gateway.core.context.GatewayToolExecutionContext;
import mcp.gateway.core.protection.McpAbuseProtectionDecision;

/**
 * Framework-neutral abuse-protection, quota, or rate-limit step used by gateway
 * governance orchestration.
 */
public interface GatewayToolProtectionEvaluator {
    /**
     * Returns whether protection should be evaluated.
     *
     * @return true when enabled
     */
    boolean enabled();

    /**
     * Evaluates protection for a normalized MCP tool context.
     *
     * @param context tool execution context
     * @return protection decision
     */
    McpAbuseProtectionDecision evaluate(GatewayToolExecutionContext context);
}
