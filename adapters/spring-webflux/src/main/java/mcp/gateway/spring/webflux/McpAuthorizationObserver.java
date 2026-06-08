package mcp.gateway.spring.webflux;

/**
 * Receives authorization observations from the adapter.
 */
@FunctionalInterface
public interface McpAuthorizationObserver {
    /**
     * Records an authorization observation.
     *
     * @param observation observation
     */
    void record(McpAuthorizationObservation observation);

    /**
     * Returns a no-op observer.
     *
     * @return no-op observer
     */
    static McpAuthorizationObserver noop() {
        return observation -> {
        };
    }
}
