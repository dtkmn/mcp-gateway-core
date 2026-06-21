package mcp.gateway.spring.webflux;

/**
 * Observes invalid MCP JSON-RPC requests rejected by the WebFlux adapter before
 * authorization, protection, or context resolution runs.
 * <p>
 * Implementations should treat the arguments as low-cardinality telemetry
 * fields. The adapter never passes the request payload to this observer.
 */
@FunctionalInterface
public interface McpInvalidRequestObserver {
    /**
     * Records an invalid request rejection without exposing request payloads.
     * <p>
     * The request id is the server/WebFlux request id, not a JSON-RPC
     * {@code id}. The correlation id is resolved through the configured
     * {@link McpGatewayCorrelationIdResolver}.
     *
     * @param reason low-cardinality rejection reason
     * @param requestId server request id
     * @param correlationId resolved correlation id, or {@code null}
     */
    void rejected(String reason, String requestId, String correlationId);

    /**
     * Returns a no-op observer.
     *
     * @return no-op observer
     */
    static McpInvalidRequestObserver noop() {
        return (reason, requestId, correlationId) -> {
        };
    }
}
