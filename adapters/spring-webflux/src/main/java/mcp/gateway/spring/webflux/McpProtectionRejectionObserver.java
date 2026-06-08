package mcp.gateway.spring.webflux;

import mcp.gateway.core.context.GatewayToolExecutionContext;
import mcp.gateway.core.protection.McpAbuseProtectionDecision;

/**
 * Receives rejected abuse-protection decisions from the adapter.
 */
@FunctionalInterface
public interface McpProtectionRejectionObserver {
    /**
     * Records a rejected decision.
     *
     * @param decision rejection decision
     * @param context execution context
     */
    void rejected(McpAbuseProtectionDecision decision, GatewayToolExecutionContext context);

    /**
     * Returns a no-op observer.
     *
     * @return no-op observer
     */
    static McpProtectionRejectionObserver noop() {
        return (decision, context) -> {
        };
    }
}
