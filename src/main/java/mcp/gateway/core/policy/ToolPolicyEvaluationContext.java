package mcp.gateway.core.policy;

import mcp.gateway.core.context.GatewayToolExecutionContext;

/**
 * Generic input for evaluating an MCP tool policy decision.
 *
 * @param toolName MCP tool name
 * @param target target URL, host, or runtime-defined target selector
 * @param correlationId request correlation identifier
 */
public record ToolPolicyEvaluationContext(
        String toolName,
        String target,
        String correlationId
) {
    /**
     * Creates a normalized policy evaluation context.
     *
     * @param toolName MCP tool name
     * @param target target URL, host, or runtime-defined target selector
     * @param correlationId request correlation identifier
     */
    public ToolPolicyEvaluationContext {
        toolName = normalize(toolName);
        target = normalize(target);
        correlationId = normalize(correlationId);
    }

    /**
     * Creates a policy context from a generic tool execution context.
     *
     * @param context tool execution context
     * @return policy evaluation context
     */
    public static ToolPolicyEvaluationContext from(GatewayToolExecutionContext context) {
        if (context == null) {
            return new ToolPolicyEvaluationContext(null, null, null);
        }
        return new ToolPolicyEvaluationContext(context.toolName(), context.target(), context.correlationId());
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
