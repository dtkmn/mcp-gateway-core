package mcp.gateway.core.authz;

import java.util.Collection;
import java.util.List;

/**
 * Result of evaluating a tool authorization request.
 * <p>
 * Prefer {@link McpToolAuthorizer} for MCP invocations or
 * {@link ToolAuthorizationPipeline#evaluate(ToolAuthorizationRequest, ToolAuthorizationRequirement)}
 * for scope-based requests. The constructor copies the scope lists but does not
 * validate relationships between the supplied flags and lists. Custom evaluators
 * remain responsible for the consistency of directly constructed decisions.
 * <p>
 * Missing scopes reflect the applied evaluation policy, not necessarily the set
 * difference between required and granted scopes. An enabled wildcard grant or
 * the authorizer's explicit disabled-check mode can produce an allowed mapped
 * decision with no missing scopes despite absent literal grants. Those policy
 * flags are not retained in this value.
 *
 * @param allowed whether the request is allowed
 * @param mapped whether the tool/action was mapped to an authorization rule
 * @param actionName normalized action name used for authorization
 * @param requiredScopes scopes required by the mapped action
 * @param grantedScopes scopes granted to the caller
 * @param missingScopes required scopes unsatisfied under the applied evaluation policy
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
     * Copies the lists and normalizes the action name without recomputing the
     * decision. A null or blank action name is rejected; other names are trimmed.
     * Null lists become empty, and non-empty lists are copied with null elements
     * rejected. Scope spelling, order, and duplicates are otherwise retained.
     * Cross-field consistency is not checked.
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
