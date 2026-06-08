package mcp.gateway.core.context;

import mcp.gateway.core.invocation.McpToolInvocation;

/**
 * MCP-neutral tool execution context shared by authorization, policy, audit,
 * and abuse-protection controls.
 *
 * @param executionContext request execution context
 * @param invocation normalized MCP invocation
 * @param target optional domain-specific target selected by the runtime
 */
public record GatewayToolExecutionContext(GatewayExecutionContext executionContext,
                                          McpToolInvocation invocation,
                                          String target) {
    /**
     * Creates a normalized tool execution context.
     */
    public GatewayToolExecutionContext {
        executionContext = executionContext == null ? GatewayExecutionContext.unknown() : executionContext;
        invocation = invocation == null ? McpToolInvocation.unknown() : invocation;
        target = GatewayExecutionContext.normalizeNullable(target);
    }

    /**
     * Creates a normalized tool execution context.
     *
     * @param executionContext request execution context
     * @param invocation normalized MCP invocation
     * @param target optional runtime target
     * @return tool execution context
     */
    public static GatewayToolExecutionContext of(GatewayExecutionContext executionContext,
                                                 McpToolInvocation invocation,
                                                 String target) {
        return new GatewayToolExecutionContext(executionContext, invocation, target);
    }

    /**
     * Creates a tool execution context from raw values.
     *
     * @param principalId caller identifier
     * @param workspaceId workspace identifier
     * @param correlationId correlation identifier
     * @param invocation normalized MCP invocation
     * @param target optional runtime target
     * @return tool execution context
     */
    public static GatewayToolExecutionContext of(String principalId,
                                                 String workspaceId,
                                                 String correlationId,
                                                 McpToolInvocation invocation,
                                                 String target) {
        return of(GatewayExecutionContext.of(principalId, workspaceId, correlationId), invocation, target);
    }

    /**
     * Returns the gateway action name for this invocation.
     *
     * @return tool name for tool calls, JSON-RPC method for other known invocations
     */
    public String actionName() {
        return invocation.actionName();
    }

    /**
     * Returns the MCP tool name for tool-call invocations.
     *
     * @return tool name, or null
     */
    public String toolName() {
        return invocation.toolName();
    }

    /**
     * Returns the JSON-RPC method.
     *
     * @return method, or null
     */
    public String method() {
        return invocation.method();
    }

    /**
     * Returns the caller identifier.
     *
     * @return principal id
     */
    public String principalId() {
        return executionContext.principalId();
    }

    /**
     * Returns the workspace identifier.
     *
     * @return workspace id
     */
    public String workspaceId() {
        return executionContext.workspaceId();
    }

    /**
     * Returns the correlation identifier.
     *
     * @return correlation id, or null
     */
    public String correlationId() {
        return executionContext.correlationId();
    }
}
