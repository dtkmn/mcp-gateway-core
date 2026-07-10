package mcp.gateway.spring.webflux;

import mcp.gateway.core.logging.CorrelationIds;
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
     * Resolves from a bounded, log-safe header value, falling back to the server request id.
     * Null or blank header names use {@code X-Correlation-Id}.
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
            String value = CorrelationIds.sanitize(
                    exchange.getRequest().getHeaders().getFirst(effectiveHeader)
            );
            if (value != null) {
                return value;
            }
            return exchange.getRequest().getId();
        };
    }
}
