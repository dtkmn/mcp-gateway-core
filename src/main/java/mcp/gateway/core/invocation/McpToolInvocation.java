package mcp.gateway.core.invocation;

/**
 * MCP JSON-RPC action visible to gateway controls.
 */
public record McpToolInvocation(McpToolInvocationKind kind, String method, String toolName) {
    public static final String METHOD_TOOLS_CALL = "tools/call";
    public static final String METHOD_TOOLS_LIST = "tools/list";

    public McpToolInvocation {
        kind = kind == null ? McpToolInvocationKind.UNKNOWN : kind;
        method = normalize(method);
        toolName = normalize(toolName);
    }

    public static McpToolInvocation unknown() {
        return new McpToolInvocation(McpToolInvocationKind.UNKNOWN, null, null);
    }

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

    public boolean authorizable() {
        return kind == McpToolInvocationKind.TOOL_CALL || kind == McpToolInvocationKind.TOOLS_LIST;
    }

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
