package mcp.gateway.core.context;

/**
 * MCP-neutral request execution context shared by gateway controls.
 *
 * @param principal caller identity
 * @param workspace workspace or tenant selector
 * @param correlationId request correlation identifier, when available
 */
public record GatewayExecutionContext(GatewayPrincipal principal,
                                      GatewayWorkspace workspace,
                                      String correlationId) {
    /**
     * Creates a normalized execution context.
     */
    public GatewayExecutionContext {
        principal = principal == null ? GatewayPrincipal.anonymous() : principal;
        workspace = workspace == null ? GatewayWorkspace.defaultWorkspace() : workspace;
        correlationId = normalizeNullable(correlationId);
    }

    /**
     * Creates a normalized execution context from raw identifiers.
     *
     * @param principalId caller identifier
     * @param workspaceId workspace identifier
     * @param correlationId correlation identifier
     * @return execution context
     */
    public static GatewayExecutionContext of(String principalId, String workspaceId, String correlationId) {
        return new GatewayExecutionContext(
                GatewayPrincipal.of(principalId),
                GatewayWorkspace.of(workspaceId),
                correlationId
        );
    }

    /**
     * Returns an unknown execution context.
     *
     * @return unknown context
     */
    public static GatewayExecutionContext unknown() {
        return of(null, null, null);
    }

    /**
     * Returns the caller identifier.
     *
     * @return principal id
     */
    public String principalId() {
        return principal.id();
    }

    /**
     * Returns the workspace identifier.
     *
     * @return workspace id
     */
    public String workspaceId() {
        return workspace.id();
    }

    static String normalizeNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
