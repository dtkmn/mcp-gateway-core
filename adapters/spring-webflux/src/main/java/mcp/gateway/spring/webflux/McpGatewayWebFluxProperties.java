package mcp.gateway.spring.webflux;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Runtime settings for the MCP gateway WebFlux governance filter.
 *
 * @param mcpEndpoint HTTP path that receives MCP JSON-RPC messages
 * @param maxBodyBytes maximum message body size buffered by the gateway filter
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
     * raised to 1024 bytes. Endpoint values with a query, fragment, matrix
     * parameters, or whitespace are rejected because they cannot identify one
     * unambiguous application-relative route.
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
        String normalized = trimmed.startsWith("/") ? trimmed : "/" + trimmed;
        validateEndpoint(normalized);
        return normalized;
    }

    private static void validateEndpoint(String endpoint) {
        URI uri;
        try {
            uri = new URI(endpoint);
        } catch (URISyntaxException exception) {
            throw invalidEndpoint(exception);
        }

        if (uri.getRawAuthority() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || uri.getRawPath() == null
                || uri.getRawPath().indexOf(';') >= 0
                || containsWhitespaceOrControl(uri.getPath())) {
            throw invalidEndpoint(null);
        }
    }

    private static boolean containsWhitespaceOrControl(String path) {
        for (int index = 0; index < path.length(); index++) {
            char value = path.charAt(index);
            if (Character.isWhitespace(value)
                    || Character.isSpaceChar(value)
                    || Character.isISOControl(value)) {
                return true;
            }
        }
        return false;
    }

    private static IllegalArgumentException invalidEndpoint(Exception cause) {
        String message = "mcpEndpoint must be a path without query, fragment, matrix parameters, or whitespace";
        return cause == null ? new IllegalArgumentException(message) : new IllegalArgumentException(message, cause);
    }
}
