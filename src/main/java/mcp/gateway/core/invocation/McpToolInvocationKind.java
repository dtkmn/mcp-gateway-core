package mcp.gateway.core.invocation;

/**
 * Classification of MCP JSON-RPC invocations relevant to gateway controls.
 */
public enum McpToolInvocationKind {
    /** A {@code tools/call} invocation with a concrete tool name. */
    TOOL_CALL,
    /** A {@code tools/list} invocation. */
    TOOLS_LIST,
    /** A known JSON-RPC method outside the gateway-controlled tool surface. */
    OTHER,
    /** A malformed or unavailable invocation. */
    UNKNOWN
}
