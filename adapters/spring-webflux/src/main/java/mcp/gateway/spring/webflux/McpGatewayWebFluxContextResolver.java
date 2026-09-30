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
     * <p>
     * The resolver may enrich caller, workspace, correlation, and target values,
     * but must preserve the supplied invocation's kind, method, and tool name.
     * A separately constructed invocation with equal values is accepted; object
     * identity is not required. Invocation values do not contain tool arguments.
     * <p>
     * When governance is active and resolution is reached, the WebFlux filter
     * rejects a null context or unequal invocation with HTTP 500 and the fixed
     * JSON error {@code invalid_execution_context}, before scope extraction,
     * authorization/protection decision callbacks, observers, or downstream
     * handling. Fully inactive governance bypasses context resolution.
     *
     * @param authentication authenticated Spring principal, or {@code null}
     *        for unauthenticated requests
     * @param exchange request exchange
     * @param invocation normalized invocation
     * @return non-null tool execution context preserving the parsed invocation
     */
    GatewayToolExecutionContext resolve(Authentication authentication,
                                        ServerWebExchange exchange,
                                        McpToolInvocation invocation);
}
