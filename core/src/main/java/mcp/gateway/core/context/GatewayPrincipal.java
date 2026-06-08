package mcp.gateway.core.context;

/**
 * MCP-neutral caller identity for gateway decisions.
 *
 * @param id stable caller identifier known to the runtime
 */
public record GatewayPrincipal(String id) {
    /** Conventional fallback principal for unauthenticated or unknown callers. */
    public static final String ANONYMOUS_ID = "anonymous";

    /**
     * Creates a normalized principal.
     */
    public GatewayPrincipal {
        id = normalize(id, ANONYMOUS_ID);
    }

    /**
     * Creates a normalized principal.
     *
     * @param id caller identifier
     * @return principal
     */
    public static GatewayPrincipal of(String id) {
        return new GatewayPrincipal(id);
    }

    /**
     * Returns the anonymous fallback principal.
     *
     * @return anonymous principal
     */
    public static GatewayPrincipal anonymous() {
        return new GatewayPrincipal(ANONYMOUS_ID);
    }

    static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
