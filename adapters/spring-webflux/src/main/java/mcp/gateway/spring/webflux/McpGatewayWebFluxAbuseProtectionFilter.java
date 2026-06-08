package mcp.gateway.spring.webflux;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import java.util.Optional;
import mcp.gateway.core.context.GatewayToolExecutionContext;
import mcp.gateway.core.invocation.McpToolInvocation;
import mcp.gateway.core.protection.McpAbuseProtectionDecision;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * WebFlux filter that applies core abuse-protection decisions to MCP JSON-RPC requests.
 */
public final class McpGatewayWebFluxAbuseProtectionFilter implements WebFilter, Ordered {
    private final ObjectMapper objectMapper;
    private final McpJsonRpcToolInvocationParser parser;
    private final McpGatewayWebFluxProperties properties;
    private final McpGatewayAbuseProtectionEvaluator protectionEvaluator;
    private final McpGatewayWebFluxContextResolver contextResolver;
    private final McpProtectionRejectionObserver rejectionObserver;
    private final McpGatewayCorrelationIdResolver correlationIdResolver;

    /**
     * Creates a filter with a no-op rejection observer.
     *
     * @param objectMapper JSON mapper used for request parsing and rejection responses
     * @param properties WebFlux adapter properties
     * @param protectionEvaluator abuse-protection evaluator backed by core contracts
     * @param contextResolver resolver that maps the request into core execution context
     */
    public McpGatewayWebFluxAbuseProtectionFilter(ObjectMapper objectMapper,
                                                  McpGatewayWebFluxProperties properties,
                                                  McpGatewayAbuseProtectionEvaluator protectionEvaluator,
                                                  McpGatewayWebFluxContextResolver contextResolver) {
        this(
                objectMapper,
                properties,
                protectionEvaluator,
                contextResolver,
                McpProtectionRejectionObserver.noop(),
                McpGatewayCorrelationIdResolver.defaultResolver()
        );
    }

    /**
     * Creates a filter.
     *
     * @param objectMapper JSON mapper used for request parsing and rejection responses
     * @param properties WebFlux adapter properties
     * @param protectionEvaluator abuse-protection evaluator backed by core contracts
     * @param contextResolver resolver that maps the request into core execution context
     * @param rejectionObserver receives rejected request observations
     * @param correlationIdResolver resolves correlation IDs for fallback responses
     */
    public McpGatewayWebFluxAbuseProtectionFilter(ObjectMapper objectMapper,
                                                  McpGatewayWebFluxProperties properties,
                                                  McpGatewayAbuseProtectionEvaluator protectionEvaluator,
                                                  McpGatewayWebFluxContextResolver contextResolver,
                                                  McpProtectionRejectionObserver rejectionObserver,
                                                  McpGatewayCorrelationIdResolver correlationIdResolver) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.parser = new McpJsonRpcToolInvocationParser(objectMapper);
        this.properties = properties == null ? McpGatewayWebFluxProperties.defaults() : properties;
        this.protectionEvaluator = Objects.requireNonNull(protectionEvaluator, "protectionEvaluator must not be null");
        this.contextResolver = Objects.requireNonNull(contextResolver, "contextResolver must not be null");
        this.rejectionObserver = rejectionObserver == null ? McpProtectionRejectionObserver.noop() : rejectionObserver;
        this.correlationIdResolver = correlationIdResolver == null
                ? McpGatewayCorrelationIdResolver.defaultResolver()
                : correlationIdResolver;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!isRelevantRequest(exchange) || !protectionEvaluator.enabled()) {
            return chain.filter(exchange);
        }
        if (McpGatewayWebFluxRequestBodies.contentLengthExceedsLimit(exchange, properties.maxBodyBytes())) {
            return writePayloadTooLarge(exchange);
        }

        return exchange.getPrincipal()
                .cast(Authentication.class)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(authentication -> cacheAndProtect(exchange, chain, authentication.orElse(null)));
    }

    private Mono<Void> cacheAndProtect(ServerWebExchange exchange,
                                       WebFilterChain chain,
                                       Authentication authentication) {
        return McpGatewayWebFluxRequestBodies.read(exchange, properties.maxBodyBytes())
                .flatMap(bodyBytes -> {
                    McpToolInvocation invocation = parser.parse(bodyBytes);
                    GatewayToolExecutionContext context = contextResolver.resolve(authentication, exchange, invocation);
                    McpAbuseProtectionDecision decision = protectionEvaluator.evaluate(context);
                    if (!decision.allowed()) {
                        rejectionObserver.rejected(decision, context);
                        return McpGatewayWebFluxResponses.protectionRejected(
                                exchange,
                                objectMapper,
                                decision,
                                context == null ? correlationIdResolver.resolve(exchange) : context.correlationId()
                        );
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
        return properties.abuseProtectionFilterOrder();
    }
}
