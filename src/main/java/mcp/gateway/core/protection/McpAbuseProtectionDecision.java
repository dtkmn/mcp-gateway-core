package mcp.gateway.core.protection;

/**
 * Result of rate-limit / quota / backpressure evaluation for an MCP request.
 *
 * @param allowed whether the request is allowed
 * @param errorCode machine-readable rejection code
 * @param reason human-readable rejection reason
 * @param toolName MCP tool name
 * @param clientId caller client identifier
 * @param workspaceId runtime workspace identifier
 * @param retryAfterSeconds retry delay for rejected requests
 */
public record McpAbuseProtectionDecision(
        boolean allowed,
        String errorCode,
        String reason,
        String toolName,
        String clientId,
        String workspaceId,
        long retryAfterSeconds
) {
    /**
     * Creates an allow decision.
     *
     * @param toolName MCP tool name
     * @param clientId caller client identifier
     * @param workspaceId runtime workspace identifier
     * @return allow decision
     */
    public static McpAbuseProtectionDecision allow(String toolName, String clientId, String workspaceId) {
        return new McpAbuseProtectionDecision(true, null, null, toolName, clientId, workspaceId, 0L);
    }

    /**
     * Creates an allow decision.
     *
     * @param context protection context
     * @return allow decision
     */
    public static McpAbuseProtectionDecision allow(McpAbuseProtectionContext context) {
        McpAbuseProtectionContext normalizedContext =
                context == null ? McpAbuseProtectionContext.of(null, null, null) : context;
        return allow(normalizedContext.toolName(), normalizedContext.clientId(), normalizedContext.workspaceId());
    }

    /**
     * Creates a reject decision.
     *
     * @param errorCode machine-readable rejection code
     * @param reason human-readable rejection reason
     * @param toolName MCP tool name
     * @param clientId caller client identifier
     * @param workspaceId runtime workspace identifier
     * @param retryAfterSeconds retry delay
     * @return reject decision
     */
    public static McpAbuseProtectionDecision reject(String errorCode,
                                                    String reason,
                                                    String toolName,
                                                    String clientId,
                                                    String workspaceId,
                                                    long retryAfterSeconds) {
        return new McpAbuseProtectionDecision(
                false,
                errorCode,
                reason,
                toolName,
                clientId,
                workspaceId,
                retryAfterSeconds
        );
    }

    /**
     * Creates a reject decision.
     *
     * @param errorCode machine-readable rejection code
     * @param reason human-readable rejection reason
     * @param context protection context
     * @param retryAfterSeconds retry delay
     * @return reject decision
     */
    public static McpAbuseProtectionDecision reject(String errorCode,
                                                    String reason,
                                                    McpAbuseProtectionContext context,
                                                    long retryAfterSeconds) {
        McpAbuseProtectionContext normalizedContext =
                context == null ? McpAbuseProtectionContext.of(null, null, null) : context;
        return reject(
                errorCode,
                reason,
                normalizedContext.toolName(),
                normalizedContext.clientId(),
                normalizedContext.workspaceId(),
                retryAfterSeconds
        );
    }
}
