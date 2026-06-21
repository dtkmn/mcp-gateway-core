package mcp.gateway.spring.webflux;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import mcp.gateway.core.context.GatewayToolExecutionContext;
import mcp.gateway.core.governance.GatewayToolAuthorizationPolicy;
import mcp.gateway.core.governance.GatewayToolGovernance;
import mcp.gateway.core.governance.GatewayToolGovernanceDecision;
import mcp.gateway.core.governance.GatewayToolGovernanceOutcome;
import mcp.gateway.core.invocation.McpToolInvocation;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * WebFlux filter that runs the shared MCP gateway governance pass once per MCP
 * JSON-RPC request.
 * <p>
 * The filter is active only for the configured MCP endpoint when authorization
 * or abuse protection governance is enabled. With governance inactive, matching
 * requests pass downstream without body buffering or validation. With governance
 * active, invalid MCP JSON-RPC request shapes are rejected before principal
 * lookup, context resolution, authorization, protection, or downstream handling.
 */
public final class McpGatewayWebFluxGovernanceFilter implements WebFilter, Ordered {
    private final ObjectMapper objectMapper;
    private final McpJsonRpcToolInvocationParser parser;
    private final McpGatewayWebFluxProperties properties;
    private final McpGatewayAuthorizationEvaluator authorizationEvaluator;
    private final McpGatewayAbuseProtectionEvaluator protectionEvaluator;
    private final McpGatewayWebFluxContextResolver contextResolver;
    private final McpGrantedScopesExtractor grantedScopesExtractor;
    private final McpAuthorizationObserver authorizationObserver;
    private final McpProtectionRejectionObserver rejectionObserver;
    private final McpGatewayCorrelationIdResolver correlationIdResolver;
    private final McpInvalidRequestObserver invalidRequestObserver;

    /**
     * Creates a filter with default scope extraction and no-op observations.
     * <p>
     * This constructor preserves the public-preview default behavior: Spring
     * Security {@code SCOPE_} authorities are used as granted scopes, correlation
     * IDs come from {@code X-Correlation-Id} with request-id fallback, and all
     * observers are no-ops.
     *
     * @param objectMapper JSON mapper used for parsing and rejection responses
     * @param properties WebFlux adapter properties
     * @param authorizationEvaluator authorization evaluator backed by core contracts
     * @param protectionEvaluator protection evaluator backed by core contracts
     * @param contextResolver resolver that maps the request into core execution context
     */
    public McpGatewayWebFluxGovernanceFilter(ObjectMapper objectMapper,
                                             McpGatewayWebFluxProperties properties,
                                             McpGatewayAuthorizationEvaluator authorizationEvaluator,
                                             McpGatewayAbuseProtectionEvaluator protectionEvaluator,
                                             McpGatewayWebFluxContextResolver contextResolver) {
        this(
                objectMapper,
                properties,
                authorizationEvaluator,
                protectionEvaluator,
                contextResolver,
                McpGrantedScopesExtractor.springSecurityScopes(),
                McpAuthorizationObserver.noop(),
                McpProtectionRejectionObserver.noop(),
                McpGatewayCorrelationIdResolver.defaultResolver()
        );
    }

    /**
     * Creates a filter.
     * <p>
     * Invalid request observation defaults to a no-op observer. Use the overload
     * that accepts {@link McpInvalidRequestObserver} when runtimes need telemetry
     * for adapter-level invalid request rejections.
     *
     * @param objectMapper JSON mapper used for parsing and rejection responses
     * @param properties WebFlux adapter properties
     * @param authorizationEvaluator authorization evaluator backed by core contracts
     * @param protectionEvaluator protection evaluator backed by core contracts
     * @param contextResolver resolver that maps the request into core execution context
     * @param grantedScopesExtractor extracts granted scopes from Spring Security authentication
     * @param authorizationObserver receives authorization observations
     * @param rejectionObserver receives protection rejection observations
     * @param correlationIdResolver resolves correlation IDs for fallback responses
     */
    public McpGatewayWebFluxGovernanceFilter(ObjectMapper objectMapper,
                                             McpGatewayWebFluxProperties properties,
                                             McpGatewayAuthorizationEvaluator authorizationEvaluator,
                                             McpGatewayAbuseProtectionEvaluator protectionEvaluator,
                                             McpGatewayWebFluxContextResolver contextResolver,
                                             McpGrantedScopesExtractor grantedScopesExtractor,
                                             McpAuthorizationObserver authorizationObserver,
                                             McpProtectionRejectionObserver rejectionObserver,
                                             McpGatewayCorrelationIdResolver correlationIdResolver) {
        this(
                objectMapper,
                properties,
                authorizationEvaluator,
                protectionEvaluator,
                contextResolver,
                grantedScopesExtractor,
                authorizationObserver,
                rejectionObserver,
                correlationIdResolver,
                McpInvalidRequestObserver.noop()
        );
    }

    /**
     * Creates a filter.
     * <p>
     * Any nullable optional collaborator falls back to the adapter default except
     * {@code objectMapper} and {@code contextResolver}, which are required.
     *
     * @param objectMapper JSON mapper used for parsing and rejection responses
     * @param properties WebFlux adapter properties
     * @param authorizationEvaluator authorization evaluator backed by core contracts
     * @param protectionEvaluator protection evaluator backed by core contracts
     * @param contextResolver resolver that maps the request into core execution context
     * @param grantedScopesExtractor extracts granted scopes from Spring Security authentication
     * @param authorizationObserver receives authorization observations
     * @param rejectionObserver receives protection rejection observations
     * @param correlationIdResolver resolves correlation IDs for fallback responses
     * @param invalidRequestObserver receives invalid request observations
     */
    public McpGatewayWebFluxGovernanceFilter(ObjectMapper objectMapper,
                                             McpGatewayWebFluxProperties properties,
                                             McpGatewayAuthorizationEvaluator authorizationEvaluator,
                                             McpGatewayAbuseProtectionEvaluator protectionEvaluator,
                                             McpGatewayWebFluxContextResolver contextResolver,
                                             McpGrantedScopesExtractor grantedScopesExtractor,
                                             McpAuthorizationObserver authorizationObserver,
                                             McpProtectionRejectionObserver rejectionObserver,
                                             McpGatewayCorrelationIdResolver correlationIdResolver,
                                             McpInvalidRequestObserver invalidRequestObserver) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.parser = new McpJsonRpcToolInvocationParser(objectMapper);
        this.properties = properties == null ? McpGatewayWebFluxProperties.defaults() : properties;
        this.authorizationEvaluator = authorizationEvaluator;
        this.protectionEvaluator = protectionEvaluator;
        this.contextResolver = Objects.requireNonNull(contextResolver, "contextResolver must not be null");
        this.grantedScopesExtractor = grantedScopesExtractor == null
                ? McpGrantedScopesExtractor.springSecurityScopes()
                : grantedScopesExtractor;
        this.authorizationObserver = authorizationObserver == null ? McpAuthorizationObserver.noop() : authorizationObserver;
        this.rejectionObserver = rejectionObserver == null ? McpProtectionRejectionObserver.noop() : rejectionObserver;
        this.correlationIdResolver = correlationIdResolver == null
                ? McpGatewayCorrelationIdResolver.defaultResolver()
                : correlationIdResolver;
        this.invalidRequestObserver = invalidRequestObserver == null ? McpInvalidRequestObserver.noop() : invalidRequestObserver;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!isRelevantRequest(exchange) || !governanceEnabled()) {
            return chain.filter(exchange);
        }
        if (McpGatewayWebFluxRequestBodies.contentLengthExceedsLimit(exchange, properties.maxBodyBytes())) {
            return writePayloadTooLarge(exchange);
        }

        return cacheAndEvaluate(exchange, chain);
    }

    private Mono<Void> cacheAndEvaluate(ServerWebExchange exchange,
                                        WebFilterChain chain) {
        return McpGatewayWebFluxRequestBodies.read(exchange, properties.maxBodyBytes())
                .flatMap(bodyBytes -> {
                    McpJsonRpcRequestClassification classification = parser.classify(bodyBytes);
                    if (!classification.valid()) {
                        return writeInvalidRequest(exchange, classification.rejectionReason());
                    }
                    McpToolInvocation invocation = classification.invocation();
                    return exchange.getPrincipal()
                            .cast(Authentication.class)
                            .map(Optional::of)
                            .defaultIfEmpty(Optional.empty())
                            .flatMap(authentication -> evaluate(
                                    exchange,
                                    chain,
                                    authentication.orElse(null),
                                    bodyBytes,
                                    invocation
                            ));
                })
                .onErrorResume(DataBufferLimitException.class, ignored -> writePayloadTooLarge(exchange));
    }

    private Mono<Void> evaluate(ServerWebExchange exchange,
                                WebFilterChain chain,
                                Authentication authentication,
                                byte[] bodyBytes,
                                McpToolInvocation invocation) {
        GatewayToolExecutionContext context = contextResolver.resolve(authentication, exchange, invocation);
        List<String> grantedScopes = grantedScopesExtractor.extract(authentication);
        GatewayToolGovernanceDecision decision = GatewayToolGovernance.evaluate(
                context,
                grantedScopes,
                authorizationEvaluator,
                protectionEvaluator
        );

        recordAuthorization(decision, context);

        if (decision.allowed()) {
            return chain.filter(McpGatewayWebFluxRequestBodies.decorate(exchange, bodyBytes));
        }
        if (decision.protectionDecision() != null && !decision.protectionDecision().allowed()) {
            rejectionObserver.rejected(decision.protectionDecision(), context);
            return McpGatewayWebFluxResponses.protectionRejected(
                    exchange,
                    objectMapper,
                    decision.protectionDecision(),
                    context == null ? correlationIdResolver.resolve(exchange) : context.correlationId()
            );
        }
        return McpGatewayWebFluxResponses.forbidden(
                exchange,
                objectMapper,
                decision.authorizationDecision(),
                decision.reason().code(),
                context == null ? correlationIdResolver.resolve(exchange) : context.correlationId()
        );
    }

    private boolean governanceEnabled() {
        return authorizationGovernanceEnabled()
                || (protectionEvaluator != null && protectionEvaluator.enabled());
    }

    private boolean authorizationGovernanceEnabled() {
        if (authorizationEvaluator == null) {
            return false;
        }
        GatewayToolAuthorizationPolicy policy = Objects.requireNonNull(
                authorizationEvaluator.policy(),
                "authorization evaluator policy must not be null"
        );
        return policy.enabled();
    }

    private boolean isRelevantRequest(ServerWebExchange exchange) {
        return exchange.getRequest().getMethod() != null
                && "POST".equalsIgnoreCase(exchange.getRequest().getMethod().name())
                && properties.mcpEndpoint().equals(exchange.getRequest().getPath().value());
    }

    private void recordAuthorization(GatewayToolGovernanceDecision decision,
                                     GatewayToolExecutionContext context) {
        if (!decision.hasAuthorizationObservation()) {
            return;
        }
        authorizationObserver.record(new McpAuthorizationObservation(
                decision.authorizationDecision().actionName(),
                observationOutcome(decision.authorizationObservationOutcome()),
                decision.authorizationObservationReason().code(),
                decision.authorizationDecision().requiredScopes(),
                decision.authorizationDecision().grantedScopes(),
                context
        ));
    }

    private String observationOutcome(GatewayToolGovernanceOutcome outcome) {
        return switch (outcome) {
            case ALLOW -> "allowed";
            case WARN -> "warn";
            case REJECT -> "denied";
        };
    }

    private Mono<Void> writePayloadTooLarge(ServerWebExchange exchange) {
        String correlationId = correlationIdResolver.resolve(exchange);
        invalidRequestObserver.rejected(
                "request_body_too_large",
                exchange.getRequest().getId(),
                correlationId
        );
        return McpGatewayWebFluxResponses.payloadTooLarge(
                exchange,
                objectMapper,
                properties.maxBodyBytes(),
                correlationId
        );
    }

    private Mono<Void> writeInvalidRequest(ServerWebExchange exchange,
                                           McpJsonRpcRequestRejectionReason reason) {
        String correlationId = correlationIdResolver.resolve(exchange);
        invalidRequestObserver.rejected(
                reason.code(),
                exchange.getRequest().getId(),
                correlationId
        );
        return McpGatewayWebFluxResponses.invalidRequest(
                exchange,
                objectMapper,
                reason.code(),
                correlationId
        );
    }

    @Override
    public int getOrder() {
        return properties.governanceFilterOrder();
    }
}
