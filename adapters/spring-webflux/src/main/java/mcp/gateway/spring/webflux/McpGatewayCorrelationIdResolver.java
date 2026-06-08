package mcp.gateway.spring.webflux;

import org.springframework.web.server.ServerWebExchange;

/**
 * Resolves a correlation identifier from a WebFlux request.
 */
@FunctionalInterface
public interface McpGatewayCorrelationIdResolver {
    /**
     * Resolves a correlation identifier.
     *
     * @param exchange request exchange
     * @return correlation id, or {@code null}
     */
    String resolve(ServerWebExchange exchange);

    /**
     * Uses {@code X-Correlation-Id} when present, falling back to the server request id.
     *
     * @return default resolver
     */
    static McpGatewayCorrelationIdResolver defaultResolver() {
        return fromHeader("X-Correlation-Id");
    }

    /**
     * Resolves from a header, falling back to the server request id.
     *
     * @param headerName header name
     * @return resolver
     */
    static McpGatewayCorrelationIdResolver fromHeader(String headerName) {
        String effectiveHeader = headerName == null || headerName.isBlank() ? "X-Correlation-Id" : headerName.trim();
        return exchange -> {
            if (exchange == null || exchange.getRequest() == null) {
                return null;
            }
            String value = exchange.getRequest().getHeaders().getFirst(effectiveHeader);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
            return exchange.getRequest().getId();
        };
    }
}
