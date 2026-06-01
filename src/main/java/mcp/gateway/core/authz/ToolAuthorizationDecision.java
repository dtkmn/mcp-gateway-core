package mcp.gateway.core.authz;

import java.util.List;

/**
 * Result of evaluating a tool authorization request.
 *
 * @param allowed whether the request is allowed
 * @param mapped whether the tool/action was mapped to an authorization rule
 * @param actionName normalized action name used for authorization
 * @param requiredScopes scopes required by the mapped action
 * @param grantedScopes scopes granted to the caller
 * @param missingScopes required scopes not granted to the caller
 */
public record ToolAuthorizationDecision(
        boolean allowed,
        boolean mapped,
        String actionName,
        List<String> requiredScopes,
        List<String> grantedScopes,
        List<String> missingScopes
) {
}
