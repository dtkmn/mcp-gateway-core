package mcp.gateway.spring.webflux;

import java.util.List;
import mcp.gateway.core.context.GatewayToolExecutionContext;

/**
 * Authorization event emitted by the WebFlux adapter.
 *
 * @param actionName evaluated action
 * @param outcome allowed, denied, or warn
 * @param reason low-cardinality reason
 * @param requiredScopes mapped required scopes
 * @param grantedScopes caller scopes
 * @param context execution context
 */
public record McpAuthorizationObservation(String actionName,
                                          String outcome,
                                          String reason,
                                          List<String> requiredScopes,
                                          List<String> grantedScopes,
                                          GatewayToolExecutionContext context) {
    /**
     * Creates an immutable observation.
     */
    public McpAuthorizationObservation {
        requiredScopes = requiredScopes == null ? List.of() : List.copyOf(requiredScopes);
        grantedScopes = grantedScopes == null ? List.of() : List.copyOf(grantedScopes);
    }
}
