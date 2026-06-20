package mcp.gateway.spring.webflux;

/**
 * Observes invalid MCP JSON-RPC requests rejected by the WebFlux adapter before
 * authorization, protection, or context resolution runs.
 */
@FunctionalInterface
public interface McpInvalidRequestObserver {
    /**
     * Records an invalid request rejection without exposing request payloads.
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
