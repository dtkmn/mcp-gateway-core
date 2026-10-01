package mcp.gateway.spring.webflux;

/**
 * Receives protocol and configuration diagnostics for adapter-controlled
 * non-execution paths not reported by the existing observers.
 * An ID-less tool call remains acknowledged with HTTP 202 even though it is
 * not executed. This is not a tool-completion or permission-denial observer.
 */
@FunctionalInterface
public interface McpAdapterRejectionObserver {
    /**
     * Records a typed diagnostic before the adapter writes its response.
     * Only the reason, server request id, and correlation id are supplied;
     * request payloads, tool arguments, and rejected resolver contexts are not.
     * <p>
     * The request id is the WebFlux request id, not the JSON-RPC id. For an
     * enforced unmapped-tool rejection, correlation comes from the
     * invocation-consistent resolved context when present, otherwise the
     * configured resolver.
     * All other reasons use the configured correlation-id resolver.
     * Implementations must not throw during normal operation. An exception
     * propagates through the filter publisher, prevents downstream execution,
     * and may prevent the normal rejection response from being written.
     *
     * @param reason typed protocol or configuration reason
     * @param requestId server request id
     * @param correlationId correlation id, or {@code null}
     */
    void rejected(McpAdapterRejectionReason reason, String requestId, String correlationId);

    /**
     * Returns an observer that discards diagnostics. Explicitly installing this
     * observer still resolves correlation ids. Omit the builder setting to
     * disable both this observation and its correlation lookup.
     *
     * @return no-op observer
     */
    static McpAdapterRejectionObserver noop() {
        return (reason, requestId, correlationId) -> {
        };
    }
}
