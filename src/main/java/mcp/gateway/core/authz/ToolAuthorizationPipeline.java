package mcp.gateway.core.authz;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Scope-based authorization evaluator for MCP tool/action calls.
 */
public final class ToolAuthorizationPipeline {
    private ToolAuthorizationPipeline() {
    }

    /**
     * Evaluates a request against its mapped requirement.
     *
     * @param request authorization request
     * @param requirement mapped requirement, or null when unmapped
     * @return authorization decision
     */
    public static ToolAuthorizationDecision evaluate(ToolAuthorizationRequest request,
                                                     ToolAuthorizationRequirement requirement) {
        Objects.requireNonNull(request, "request must not be null");
        if (requirement == null) {
            return new ToolAuthorizationDecision(
                    false,
                    false,
                    request.actionName(),
                    List.of(),
                    request.grantedScopes(),
                    List.of()
            );
        }
        if (!request.actionName().equals(requirement.actionName())) {
            throw new IllegalArgumentException("authorization requirement action does not match request action");
        }

        Set<String> grantedScopeSet = new LinkedHashSet<>(request.grantedScopes());
        boolean hasWildcard = request.wildcardAllowed() && grantedScopeSet.contains("*");
        List<String> missingScopes = new ArrayList<>();
        for (String requiredScope : requirement.requiredScopes()) {
            if (!hasWildcard && !grantedScopeSet.contains(requiredScope)) {
                missingScopes.add(requiredScope);
            }
        }

        return new ToolAuthorizationDecision(
                missingScopes.isEmpty(),
                true,
                request.actionName(),
                requirement.requiredScopes(),
                request.grantedScopes(),
                missingScopes
        );
    }
}
