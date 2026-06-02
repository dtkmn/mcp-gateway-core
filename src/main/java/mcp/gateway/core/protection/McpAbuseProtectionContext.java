package mcp.gateway.core.protection;

/**
 * MCP-neutral context for abuse-protection and quota decisions.
 *
 * @param toolName MCP tool name, when available
 * @param clientId caller client identifier
 * @param workspaceId runtime workspace identifier
 */
public record McpAbuseProtectionContext(String toolName,
                                        String clientId,
                                        String workspaceId) {
    /**
     * Creates a normalized protection context.
     */
    public McpAbuseProtectionContext {
        toolName = normalize(toolName);
        clientId = normalize(clientId);
        workspaceId = normalize(workspaceId);
    }

    /**
     * Creates a normalized protection context.
     *
     * @param toolName MCP tool name
     * @param clientId caller client identifier
     * @param workspaceId runtime workspace identifier
     * @return protection context
     */
    public static McpAbuseProtectionContext of(String toolName, String clientId, String workspaceId) {
        return new McpAbuseProtectionContext(toolName, clientId, workspaceId);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
