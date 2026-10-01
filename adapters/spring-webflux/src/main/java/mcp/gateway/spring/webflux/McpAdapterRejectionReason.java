package mcp.gateway.spring.webflux;

/**
 * Typed reasons for adapter-controlled non-execution paths that are not
 * authorization, abuse-protection, or invalid-body observations.
 * The reason codes are suitable for low-cardinality telemetry labels.
 */
public enum McpAdapterRejectionReason {
    /** A registry-controlled tool call has an invalid explicit JSON-RPC id. */
    INVALID_TOOL_CALL_ID("invalid_tool_call_id"),
    /** An ID-less tool call is acknowledged without execution or a reply. */
    TOOL_CALL_WITHOUT_ID("tool_call_without_id"),
    /** The requested tool is absent from the configured active registry. */
    UNKNOWN_TOOL("unknown_tool"),
    /** A registry-known tool has no permission mapping under enforcement. */
    UNMAPPED_TOOL("unmapped_tool"),
    /** The context resolver returned null or changed the parsed invocation. */
    INVALID_EXECUTION_CONTEXT("invalid_execution_context");

    private final String code;

    McpAdapterRejectionReason(String code) {
        this.code = code;
    }

    /**
     * Returns the stable reason code.
     *
     * @return low-cardinality code
     */
    public String code() {
        return code;
    }
}
