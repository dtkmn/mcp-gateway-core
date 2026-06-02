package mcp.gateway.core.authz;

import java.util.Collection;
import java.util.List;

/**
 * Required scopes for a mapped MCP tool/action.
 * <p>
 * Empty requirements are invalid. Use a null requirement when an action is
 * intentionally unmapped.
 *
 * @param actionName MCP action or tool name
 * @param requiredScopes non-empty normalized required scopes
 */
public record ToolAuthorizationRequirement(String actionName,
                                           List<String> requiredScopes) {

    /**
     * Creates an authorization requirement.
     */
    public ToolAuthorizationRequirement {
        actionName = ToolAuthorizationDecision.normalizeActionName(actionName);
        requiredScopes = ToolAuthorizationRequest.normalizeScopes(requiredScopes);
        if (requiredScopes.isEmpty()) {
            throw new IllegalArgumentException("authorization requirement scopes must not be empty");
        }
    }

    /**
     * Creates an authorization requirement from raw scopes.
     *
     * @param actionName MCP action or tool name
     * @param requiredScopes raw required scopes
     * @return requirement
     */
    public static ToolAuthorizationRequirement of(String actionName, Collection<String> requiredScopes) {
        return new ToolAuthorizationRequirement(actionName, ToolAuthorizationRequest.normalizeScopes(requiredScopes));
    }
}
