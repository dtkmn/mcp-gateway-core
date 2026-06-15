package mcp.gateway.core.governance;

import java.util.Collection;
import mcp.gateway.core.authz.ToolAuthorizationDecision;
import mcp.gateway.core.context.GatewayToolExecutionContext;

/**
 * Framework-neutral authorization step used by gateway governance orchestration.
 */
public interface GatewayToolAuthorizationEvaluator {
    /**
     * Current authorization policy.
     *
     * @return policy
     */
    GatewayToolAuthorizationPolicy policy();

    /**
     * Evaluates authorization for a normalized MCP tool context.
     *
     * @param grantedScopes scopes granted to the caller
     * @param context tool execution context
     * @return authorization decision
     */
    ToolAuthorizationDecision authorize(Collection<String> grantedScopes, GatewayToolExecutionContext context);
}
