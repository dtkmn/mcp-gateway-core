package mcp.gateway.spring.webflux;

import java.util.Collection;
import mcp.gateway.core.authz.ToolAuthorizationDecision;
import mcp.gateway.core.context.GatewayToolExecutionContext;
import mcp.gateway.core.governance.GatewayToolAuthorizationEvaluator;
import mcp.gateway.core.governance.GatewayToolAuthorizationPolicy;

/**
 * Evaluates authorization for a parsed MCP invocation.
 */
public interface McpGatewayAuthorizationEvaluator extends GatewayToolAuthorizationEvaluator {
    /**
     * Returns the current authorization mode.
     * <p>
     * The default {@link #policy()} implementation maps {@code DISABLED} to a
     * disabled core policy, and maps {@code WARN} and {@code ENFORCE} to enabled
     * core policies with different rejection behavior.
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

    @Override
    default GatewayToolAuthorizationPolicy policy() {
        return switch (mode()) {
            case DISABLED -> GatewayToolAuthorizationPolicy.disabled();
            case WARN -> GatewayToolAuthorizationPolicy.warn();
            case ENFORCE -> GatewayToolAuthorizationPolicy.enforce();
        };
    }

    /**
     * Returns whether authorization should be evaluated.
     * <p>
     * This legacy helper is derived from {@link #mode()}. The WebFlux governance
     * filter uses {@link #policy()} as the source of truth so custom evaluators
     * can override the full policy without changing constructor compatibility.
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
