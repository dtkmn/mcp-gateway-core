package mcp.gateway.spring.webflux;

/**
 * Runtime settings shared by MCP gateway WebFlux filters.
 *
 * @param mcpEndpoint HTTP path that receives MCP JSON-RPC requests
 * @param maxBodyBytes maximum request body size buffered by gateway filters
 * @param authorizationFilterOrder Spring WebFilter order for authorization
 * @param abuseProtectionFilterOrder Spring WebFilter order for abuse protection
 */
public record McpGatewayWebFluxProperties(String mcpEndpoint,
                                          int maxBodyBytes,
                                          int authorizationFilterOrder,
                                          int abuseProtectionFilterOrder) {
    /** Default MCP endpoint used by Spring AI MCP server WebFlux setups. */
    public static final String DEFAULT_MCP_ENDPOINT = "/mcp";
    /** Conservative default matching the current downstream security-pack runtime. */
    public static final int DEFAULT_MAX_BODY_BYTES = 262_144;

    /**
     * Creates normalized WebFlux gateway settings.
     */
    public McpGatewayWebFluxProperties {
        mcpEndpoint = normalizeEndpoint(mcpEndpoint);
        maxBodyBytes = Math.max(1024, maxBodyBytes);
    }

    /**
     * Returns default settings with neutral filter ordering.
     *
     * @return default settings
     */
    public static McpGatewayWebFluxProperties defaults() {
        return new McpGatewayWebFluxProperties(DEFAULT_MCP_ENDPOINT, DEFAULT_MAX_BODY_BYTES, 0, 1);
    }

    private static String normalizeEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return DEFAULT_MCP_ENDPOINT;
        }
        String trimmed = endpoint.trim();
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }
}
