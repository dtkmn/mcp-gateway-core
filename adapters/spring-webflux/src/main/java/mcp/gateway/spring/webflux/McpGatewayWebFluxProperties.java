package mcp.gateway.spring.webflux;

/**
 * Runtime settings for the MCP gateway WebFlux governance filter.
 *
 * @param mcpEndpoint HTTP path that receives MCP JSON-RPC requests
 * @param maxBodyBytes maximum request body size buffered by the gateway filter
 *        while governance is active; values below 1024 normalize to 1024
 * @param governanceFilterOrder Spring WebFilter order for the governance filter
 */
public record McpGatewayWebFluxProperties(String mcpEndpoint,
                                          int maxBodyBytes,
                                          int governanceFilterOrder) {
    /** Default MCP endpoint used by Spring AI MCP server WebFlux setups. */
    public static final String DEFAULT_MCP_ENDPOINT = "/mcp";
    /** Default maximum buffered MCP request body size, 256 KiB. */
    public static final int DEFAULT_MAX_BODY_BYTES = 262_144;

    /**
     * Creates normalized WebFlux gateway settings.
     * <p>
     * Blank endpoints default to {@value #DEFAULT_MCP_ENDPOINT}, endpoints
     * without a leading slash gain one, and body limits below 1024 bytes are
     * raised to 1024 bytes.
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
        return new McpGatewayWebFluxProperties(DEFAULT_MCP_ENDPOINT, DEFAULT_MAX_BODY_BYTES, 0);
    }

    private static String normalizeEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return DEFAULT_MCP_ENDPOINT;
        }
        String trimmed = endpoint.trim();
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }
}
