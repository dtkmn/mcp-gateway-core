package mcp.gateway.spring.webflux;

import mcp.gateway.core.context.GatewayToolExecutionContext;
import mcp.gateway.core.invocation.McpToolInvocation;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ServerWebExchange;

/**
 * Adapts Spring request authentication into a core tool execution context.
 */
@FunctionalInterface
public interface McpGatewayWebFluxContextResolver {
    /**
     * Resolves the core context for a parsed invocation.
     *
     * @param authentication authenticated Spring principal, or {@code null}
     *        for unauthenticated requests
     * @param exchange request exchange
     * @param invocation normalized invocation
     * @return non-null tool execution context
     */
    GatewayToolExecutionContext resolve(Authentication authentication,
                                        ServerWebExchange exchange,
                                        McpToolInvocation invocation);
}
