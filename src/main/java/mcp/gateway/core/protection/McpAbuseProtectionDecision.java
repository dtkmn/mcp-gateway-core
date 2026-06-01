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
}
