package mcp.gateway.core.invocation;

/**
 * MCP JSON-RPC action visible to gateway controls.
 *
 * @param kind normalized invocation kind
 * @param method normalized JSON-RPC method, or {@code null} when unavailable
 * @param toolName normalized MCP tool name for {@code tools/call}, or {@code null}
 */
public record McpToolInvocation(McpToolInvocationKind kind, String method, String toolName) {
    /** JSON-RPC method used by MCP clients to invoke a tool. */
    public static final String METHOD_TOOLS_CALL = "tools/call";
    /** JSON-RPC method used by MCP clients to list available tools. */
    public static final String METHOD_TOOLS_LIST = "tools/list";

    /**
     * Creates a normalized invocation value.
     *
     * @param kind invocation kind
     * @param method JSON-RPC method
     * @param toolName MCP tool name
     */
    public McpToolInvocation {
        kind = kind == null ? McpToolInvocationKind.UNKNOWN : kind;
        method = normalize(method);
        toolName = normalize(toolName);
    }

    /**
     * Creates an unknown invocation marker.
     *
     * @return unknown invocation
     */
    public static McpToolInvocation unknown() {
        return new McpToolInvocation(McpToolInvocationKind.UNKNOWN, null, null);
    }

    /**
     * Converts a JSON-RPC method and optional tool name into a normalized invocation.
     *
     * @param method JSON-RPC method
     * @param toolName MCP tool name for {@code tools/call}
     * @return normalized invocation
     */
    public static McpToolInvocation fromJsonRpc(String method, String toolName) {
        String normalizedMethod = normalize(method);
        if (METHOD_TOOLS_LIST.equals(normalizedMethod)) {
            return new McpToolInvocation(McpToolInvocationKind.TOOLS_LIST, METHOD_TOOLS_LIST, null);
        }
        if (METHOD_TOOLS_CALL.equals(normalizedMethod)) {
            String normalizedTool = normalize(toolName);
            if (normalizedTool == null) {
                return new McpToolInvocation(McpToolInvocationKind.UNKNOWN, METHOD_TOOLS_CALL, null);
            }
            return new McpToolInvocation(McpToolInvocationKind.TOOL_CALL, METHOD_TOOLS_CALL, normalizedTool);
        }
        return new McpToolInvocation(
                normalizedMethod == null ? McpToolInvocationKind.UNKNOWN : McpToolInvocationKind.OTHER,
                normalizedMethod,
                null
        );
    }

    /**
     * Returns whether the invocation should pass through gateway authorization controls.
     *
     * @return {@code true} for tool calls and tool listing
     */
    public boolean authorizable() {
        return kind == McpToolInvocationKind.TOOL_CALL || kind == McpToolInvocationKind.TOOLS_LIST;
    }

    /**
     * Returns the gateway action name for authorization and audit.
     *
     * @return tool name for tool calls; otherwise JSON-RPC method
     */
    public String actionName() {
        return kind == McpToolInvocationKind.TOOL_CALL ? toolName : method;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
