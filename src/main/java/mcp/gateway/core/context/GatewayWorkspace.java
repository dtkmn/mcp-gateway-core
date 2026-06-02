package mcp.gateway.core.context;

/**
 * MCP-neutral workspace, tenant, or isolation selector for gateway decisions.
 *
 * @param id stable workspace or tenant identifier known to the runtime
 */
public record GatewayWorkspace(String id) {
    /** Conventional fallback workspace for runtimes without explicit tenancy. */
    public static final String DEFAULT_ID = "default-workspace";

    /**
     * Creates a normalized workspace selector.
     */
    public GatewayWorkspace {
        id = GatewayPrincipal.normalize(id, DEFAULT_ID);
    }

    /**
     * Creates a normalized workspace selector.
     *
     * @param id workspace identifier
     * @return workspace selector
     */
    public static GatewayWorkspace of(String id) {
        return new GatewayWorkspace(id);
    }

    /**
     * Returns the default workspace selector.
     *
     * @return default workspace
     */
    public static GatewayWorkspace defaultWorkspace() {
        return new GatewayWorkspace(DEFAULT_ID);
    }
}
