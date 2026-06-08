package mcp.gateway.core.authz;

import java.util.Collection;
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
    /**
     * Creates an immutable decision.
     */
    public ToolAuthorizationDecision {
        actionName = normalizeActionName(actionName);
        requiredScopes = immutableScopes(requiredScopes);
        grantedScopes = immutableScopes(grantedScopes);
        missingScopes = immutableScopes(missingScopes);
    }

    static String normalizeActionName(String actionName) {
        if (actionName == null || actionName.isBlank()) {
            throw new IllegalArgumentException("authorization action name must not be blank");
        }
        return actionName.trim();
    }

    static List<String> immutableScopes(Collection<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return List.of();
        }
        return List.copyOf(scopes);
    }
}
