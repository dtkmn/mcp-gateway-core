package mcp.gateway.spring.webflux;

/**
 * Runtime behavior for mapped MCP tool authorization decisions.
 */
public enum McpGatewayAuthorizationMode {
    /** Do not evaluate tool authorization in the adapter. */
    DISABLED,
    /** Record warnings but pass denied mapped requests through. */
    WARN,
    /** Reject denied mapped requests. */
    ENFORCE
}
