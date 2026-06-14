package mcp.gateway.spring.webflux;

import mcp.gateway.core.context.GatewayToolExecutionContext;
import mcp.gateway.core.governance.GatewayToolProtectionEvaluator;
import mcp.gateway.core.protection.McpAbuseProtectionDecision;

/**
 * Evaluates abuse protection, quotas, or rate limits for an MCP invocation.
 */
public interface McpGatewayAbuseProtectionEvaluator extends GatewayToolProtectionEvaluator {
    /**
     * Returns whether the adapter should evaluate abuse protection.
     *
     * @return true when enabled
     */
    boolean enabled();

    /**
     * Evaluates the request.
     *
     * @param context tool execution context
     * @return allow or reject decision
     */
    McpAbuseProtectionDecision evaluate(GatewayToolExecutionContext context);
}
