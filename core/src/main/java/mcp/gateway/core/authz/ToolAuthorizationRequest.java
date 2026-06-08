package mcp.gateway.core.authz;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Authorization input for one MCP tool/action request.
 *
 * @param actionName normalized MCP action or tool name
 * @param grantedScopes normalized caller scopes
 * @param wildcardAllowed whether "*" grants all required scopes
 */
public record ToolAuthorizationRequest(String actionName,
                                       List<String> grantedScopes,
                                       boolean wildcardAllowed) {

    /**
     * Creates an authorization request.
     */
    public ToolAuthorizationRequest {
        actionName = ToolAuthorizationDecision.normalizeActionName(actionName);
        grantedScopes = normalizeScopes(grantedScopes);
    }

    /**
     * Creates an authorization request from raw granted scopes.
     *
     * @param actionName MCP action or tool name
     * @param grantedScopes raw caller scopes
     * @param wildcardAllowed whether "*" grants all required scopes
     * @return request
     */
    public static ToolAuthorizationRequest of(String actionName,
                                              Collection<String> grantedScopes,
                                              boolean wildcardAllowed) {
        return new ToolAuthorizationRequest(actionName, normalizeScopes(grantedScopes), wildcardAllowed);
    }

    /**
     * Normalizes scope strings for authorization comparison.
     *
     * @param scopes raw scopes
     * @return normalized scopes
     */
    public static List<String> normalizeScopes(Collection<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String scope : scopes) {
            if (scope != null && !scope.isBlank()) {
                normalized.add(scope.trim().toLowerCase(Locale.ROOT));
            }
        }
        return List.copyOf(normalized);
    }
}
