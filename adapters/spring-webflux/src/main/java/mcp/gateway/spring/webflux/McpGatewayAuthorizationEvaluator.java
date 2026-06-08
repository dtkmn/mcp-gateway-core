package mcp.gateway.spring.webflux;

import java.util.Collection;
import mcp.gateway.core.authz.ToolAuthorizationDecision;
import mcp.gateway.core.context.GatewayToolExecutionContext;

/**
 * Evaluates authorization for a parsed MCP invocation.
 */
public interface McpGatewayAuthorizationEvaluator {
    /**
     * Returns the current authorization mode.
     *
     * @return authorization mode
     */
    McpGatewayAuthorizationMode mode();

    /**
     * Evaluates authorization.
     *
     * @param grantedScopes scopes granted to the caller
     * @param context tool execution context
     * @return authorization decision
     */
    ToolAuthorizationDecision authorize(Collection<String> grantedScopes, GatewayToolExecutionContext context);

    /**
     * Returns whether authorization should be evaluated.
     *
     * @return true when enabled
     */
    default boolean enabled() {
        return mode() != McpGatewayAuthorizationMode.DISABLED;
    }

    /**
     * Returns whether denied mapped requests should be rejected.
     *
     * @return true when enforced
     */
    default boolean enforced() {
        return mode() == McpGatewayAuthorizationMode.ENFORCE;
    }

    /**
     * Returns whether denied mapped requests should be passed with warning observations.
     *
     * @return true when warn-only
     */
    default boolean warnOnly() {
        return mode() == McpGatewayAuthorizationMode.WARN;
    }
}
