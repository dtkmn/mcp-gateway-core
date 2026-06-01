package mcp.gateway.core.policy;

/**
 * Generic input for evaluating an MCP tool policy decision.
 */
public record ToolPolicyEvaluationContext(
        String toolName,
        String target,
        String correlationId
) {
    public ToolPolicyEvaluationContext {
        toolName = normalize(toolName);
        target = normalize(target);
        correlationId = normalize(correlationId);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
