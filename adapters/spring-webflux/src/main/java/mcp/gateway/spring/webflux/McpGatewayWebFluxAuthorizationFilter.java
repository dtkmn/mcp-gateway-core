package mcp.gateway.spring.webflux;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import mcp.gateway.core.authz.ToolAuthorizationDecision;
import mcp.gateway.core.context.GatewayToolExecutionContext;
import mcp.gateway.core.invocation.McpToolInvocation;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * WebFlux filter that applies core MCP tool authorization to JSON-RPC requests.
 */
public final class McpGatewayWebFluxAuthorizationFilter implements WebFilter, Ordered {
    private final ObjectMapper objectMapper;
    private final McpJsonRpcToolInvocationParser parser;
    private final McpGatewayWebFluxProperties properties;
    private final McpGatewayAuthorizationEvaluator authorizationEvaluator;
    private final McpGatewayWebFluxContextResolver contextResolver;
    private final McpGrantedScopesExtractor grantedScopesExtractor;
    private final McpAuthorizationObserver authorizationObserver;
    private final McpGatewayCorrelationIdResolver correlationIdResolver;

    /**
     * Creates a filter with default scope extraction and no-op observations.
     *
     * @param objectMapper JSON mapper used for request parsing and denial responses
     * @param properties WebFlux adapter properties
     * @param authorizationEvaluator authorization evaluator backed by core contracts
     * @param contextResolver resolver that maps the request into core execution context
     */
    public McpGatewayWebFluxAuthorizationFilter(ObjectMapper objectMapper,
                                                McpGatewayWebFluxProperties properties,
                                                McpGatewayAuthorizationEvaluator authorizationEvaluator,
                                                McpGatewayWebFluxContextResolver contextResolver) {
        this(
                objectMapper,
                properties,
                authorizationEvaluator,
                contextResolver,
                McpGrantedScopesExtractor.springSecurityScopes(),
                McpAuthorizationObserver.noop(),
                McpGatewayCorrelationIdResolver.defaultResolver()
        );
    }

    /**
     * Creates a filter.
     *
     * @param objectMapper JSON mapper used for request parsing and denial responses
     * @param properties WebFlux adapter properties
     * @param authorizationEvaluator authorization evaluator backed by core contracts
     * @param contextResolver resolver that maps the request into core execution context
     * @param grantedScopesExtractor extracts granted scopes from Spring Security authentication
     * @param authorizationObserver receives allow, warn, and deny observations
     * @param correlationIdResolver resolves correlation IDs for fallback responses
     */
    public McpGatewayWebFluxAuthorizationFilter(ObjectMapper objectMapper,
                                                McpGatewayWebFluxProperties properties,
                                                McpGatewayAuthorizationEvaluator authorizationEvaluator,
                                                McpGatewayWebFluxContextResolver contextResolver,
                                                McpGrantedScopesExtractor grantedScopesExtractor,
                                                McpAuthorizationObserver authorizationObserver,
                                                McpGatewayCorrelationIdResolver correlationIdResolver) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.parser = new McpJsonRpcToolInvocationParser(objectMapper);
        this.properties = properties == null ? McpGatewayWebFluxProperties.defaults() : properties;
        this.authorizationEvaluator = Objects.requireNonNull(
                authorizationEvaluator,
                "authorizationEvaluator must not be null"
        );
        this.contextResolver = Objects.requireNonNull(contextResolver, "contextResolver must not be null");
        this.grantedScopesExtractor = grantedScopesExtractor == null
                ? McpGrantedScopesExtractor.springSecurityScopes()
                : grantedScopesExtractor;
        this.authorizationObserver = authorizationObserver == null ? McpAuthorizationObserver.noop() : authorizationObserver;
        this.correlationIdResolver = correlationIdResolver == null
                ? McpGatewayCorrelationIdResolver.defaultResolver()
                : correlationIdResolver;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!isRelevantRequest(exchange) || !authorizationEvaluator.enabled()) {
            return chain.filter(exchange);
        }
        if (McpGatewayWebFluxRequestBodies.contentLengthExceedsLimit(exchange, properties.maxBodyBytes())) {
            return writePayloadTooLarge(exchange);
        }

        return exchange.getPrincipal()
                .cast(Authentication.class)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(authentication -> cacheAndAuthorize(exchange, chain, authentication.orElse(null)));
    }

    private Mono<Void> cacheAndAuthorize(ServerWebExchange exchange,
                                         WebFilterChain chain,
                                         Authentication authentication) {
        return McpGatewayWebFluxRequestBodies.read(exchange, properties.maxBodyBytes())
                .flatMap(bodyBytes -> {
                    McpToolInvocation invocation = parser.parse(bodyBytes);
                    if (!invocation.authorizable()) {
                        return chain.filter(McpGatewayWebFluxRequestBodies.decorate(exchange, bodyBytes));
                    }

                    List<String> grantedScopes = grantedScopesExtractor.extract(authentication);
                    GatewayToolExecutionContext context = contextResolver.resolve(authentication, exchange, invocation);
                    ToolAuthorizationDecision decision = authorizationEvaluator.authorize(grantedScopes, context);

                    if (!decision.mapped()) {
                        record(decision, authorizationEvaluator.enforced() ? "denied" : "warn", "unmapped_tool", context);
                        if (authorizationEvaluator.enforced()) {
                            return writeForbidden(exchange, decision, "unmapped_tool", context);
                        }
                    }

                    if (!decision.allowed()) {
                        record(decision, authorizationEvaluator.warnOnly() ? "warn" : "denied", "insufficient_scope", context);
                        if (!authorizationEvaluator.warnOnly()) {
                            return writeForbidden(exchange, decision, "insufficient_scope", context);
                        }
                    } else {
                        record(decision, "allowed", "scope_granted", context);
                    }

                    return chain.filter(McpGatewayWebFluxRequestBodies.decorate(exchange, bodyBytes));
                })
                .onErrorResume(DataBufferLimitException.class, ignored -> writePayloadTooLarge(exchange));
    }

    private boolean isRelevantRequest(ServerWebExchange exchange) {
        return exchange.getRequest().getMethod() != null
                && "POST".equalsIgnoreCase(exchange.getRequest().getMethod().name())
                && properties.mcpEndpoint().equals(exchange.getRequest().getPath().value());
    }

    private void record(ToolAuthorizationDecision decision,
                        String outcome,
                        String reason,
                        GatewayToolExecutionContext context) {
        authorizationObserver.record(new McpAuthorizationObservation(
                decision.actionName(),
                outcome,
                reason,
                decision.requiredScopes(),
                decision.grantedScopes(),
                context
        ));
    }

    private Mono<Void> writeForbidden(ServerWebExchange exchange,
                                      ToolAuthorizationDecision decision,
                                      String errorCode,
                                      GatewayToolExecutionContext context) {
        return McpGatewayWebFluxResponses.forbidden(
                exchange,
                objectMapper,
                decision,
                errorCode,
                context == null ? correlationIdResolver.resolve(exchange) : context.correlationId()
        );
    }

    private Mono<Void> writePayloadTooLarge(ServerWebExchange exchange) {
        return McpGatewayWebFluxResponses.payloadTooLarge(
                exchange,
                objectMapper,
                properties.maxBodyBytes(),
                correlationIdResolver.resolve(exchange)
        );
    }

    @Override
    public int getOrder() {
        return properties.authorizationFilterOrder();
    }
}
