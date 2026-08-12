package mcp.gateway.spring.webflux;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import mcp.gateway.core.authz.ToolAuthorizationDecision;
import mcp.gateway.core.context.GatewayToolExecutionContext;
import mcp.gateway.core.governance.GatewayToolAuthorizationPolicy;
import mcp.gateway.core.governance.GatewayToolGovernance;
import mcp.gateway.core.governance.GatewayToolGovernanceDecision;
import mcp.gateway.core.governance.GatewayToolGovernanceOutcome;
import mcp.gateway.core.invocation.McpToolInvocation;
import mcp.gateway.core.protection.McpAbuseProtectionDecision;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.server.PathContainer;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * WebFlux filter that runs the shared MCP gateway governance pass once per MCP
 * JSON-RPC request message.
 * <p>
 * The filter is active only for the configured MCP endpoint when authorization
 * or abuse protection governance is enabled. With governance inactive, matching
 * requests pass downstream without body buffering or validation. With governance
 * active, recognized JSON-RPC response envelopes pass downstream without request
 * governance, while invalid MCP JSON-RPC message shapes are rejected before
 * principal lookup, context resolution, authorization, protection, or downstream
 * handling. Invalid or oversized bodies are reported through
 * {@link McpInvalidRequestObserver}; authorization and protection observers
 * are used only after a request has a valid governance shape.
 */
public final class McpGatewayWebFluxGovernanceFilter implements WebFilter, Ordered {
    private final JsonMapper jsonMapper;
    private final McpJsonRpcToolInvocationParser parser;
    private final McpGatewayWebFluxProperties properties;
    private final PathContainer mcpEndpointPath;
    private final McpGatewayAuthorizationEvaluator authorizationEvaluator;
    private final McpGatewayAbuseProtectionEvaluator protectionEvaluator;
    private final McpGatewayWebFluxContextResolver contextResolver;
    private final McpGrantedScopesExtractor grantedScopesExtractor;
    private final McpAuthorizationObserver authorizationObserver;
    private final McpProtectionRejectionObserver rejectionObserver;
    private final McpGatewayCorrelationIdResolver correlationIdResolver;
    private final McpInvalidRequestObserver invalidRequestObserver;

    /**
     * Starts fluent configuration of a WebFlux governance filter.
     * <p>
     * The JSON mapper and context resolver are required because the adapter cannot
     * safely infer request parsing, principal, or workspace semantics. All other
     * collaborators retain the defaults documented by {@link Builder}.
     *
     * @param jsonMapper JSON mapper used for parsing and rejection responses
     * @param contextResolver resolver that maps requests into core execution context
     * @return a new filter builder
     */
    public static Builder builder(JsonMapper jsonMapper,
                                  McpGatewayWebFluxContextResolver contextResolver) {
        return new Builder(jsonMapper, contextResolver);
    }

    /**
     * Fluent, named configuration for {@link McpGatewayWebFluxGovernanceFilter}.
     * <p>
     * Omitted optional collaborators use the same defaults as the public
     * constructors: default WebFlux properties, Spring Security {@code SCOPE_}
     * extraction, no-op observers, and the default correlation-id resolver.
     * At least one authorization or protection evaluator must be configured so
     * the builder catches accidental omission of both governance concerns.
     * A configured dynamic evaluator may still disable governance at request time.
     */
    public static final class Builder {
        private final JsonMapper jsonMapper;
        private final McpGatewayWebFluxContextResolver contextResolver;
        private McpGatewayWebFluxProperties properties = McpGatewayWebFluxProperties.defaults();
        private McpGatewayAuthorizationEvaluator authorizationEvaluator;
        private McpGatewayAbuseProtectionEvaluator protectionEvaluator;
        private McpGrantedScopesExtractor grantedScopesExtractor =
                McpGrantedScopesExtractor.springSecurityScopes();
        private McpAuthorizationObserver authorizationObserver = McpAuthorizationObserver.noop();
        private McpProtectionRejectionObserver rejectionObserver = McpProtectionRejectionObserver.noop();
        private McpGatewayCorrelationIdResolver correlationIdResolver = McpGatewayCorrelationIdResolver.defaultResolver();
        private McpInvalidRequestObserver invalidRequestObserver = McpInvalidRequestObserver.noop();

        private Builder(JsonMapper jsonMapper,
                        McpGatewayWebFluxContextResolver contextResolver) {
            this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper must not be null");
            this.contextResolver = Objects.requireNonNull(contextResolver, "contextResolver must not be null");
        }

        /**
         * Uses the supplied endpoint, request-body limit, and filter order.
         *
         * @param properties WebFlux adapter properties
         * @return this builder
         */
        public Builder properties(McpGatewayWebFluxProperties properties) {
            this.properties = Objects.requireNonNull(properties, "properties must not be null");
            return this;
        }

        /**
         * Uses an existing authorization evaluator.
         *
         * @param evaluator authorization evaluator backed by core contracts
         * @return this builder
         */
        public Builder authorizationEvaluator(McpGatewayAuthorizationEvaluator evaluator) {
            this.authorizationEvaluator = Objects.requireNonNull(evaluator, "evaluator must not be null");
            return this;
        }

        /**
         * Adapts dynamic authorization mode and decision callbacks without an
         * anonymous {@link McpGatewayAuthorizationEvaluator} implementation.
         * The mode is read at request time, and the authorization callback runs
         * when the resulting policy enables authorization for the invocation.
         *
         * @param mode supplies the current authorization mode
         * @param authorize evaluates granted scopes and resolved execution context
         * @return this builder
         */
        public Builder authorization(
                Supplier<McpGatewayAuthorizationMode> mode,
                BiFunction<Collection<String>, GatewayToolExecutionContext, ToolAuthorizationDecision> authorize
        ) {
            Supplier<McpGatewayAuthorizationMode> requiredMode = Objects.requireNonNull(mode, "mode must not be null");
            BiFunction<Collection<String>, GatewayToolExecutionContext, ToolAuthorizationDecision> requiredAuthorize =
                    Objects.requireNonNull(authorize, "authorize must not be null");
            return authorizationEvaluator(new McpGatewayAuthorizationEvaluator() {
                @Override
                public McpGatewayAuthorizationMode mode() {
                    return requiredMode.get();
                }

                @Override
                public ToolAuthorizationDecision authorize(Collection<String> grantedScopes,
                                                           GatewayToolExecutionContext context) {
                    return requiredAuthorize.apply(grantedScopes, context);
                }
            });
        }

        /**
         * Uses an existing abuse-protection evaluator.
         *
         * @param evaluator abuse-protection, quota, or rate-limit evaluator
         * @return this builder
         */
        public Builder protectionEvaluator(McpGatewayAbuseProtectionEvaluator evaluator) {
            this.protectionEvaluator = Objects.requireNonNull(evaluator, "evaluator must not be null");
            return this;
        }

        /**
         * Adapts dynamic protection enablement and decision callbacks without an
         * anonymous {@link McpGatewayAbuseProtectionEvaluator} implementation.
         * Enablement is read at request time, and the decision callback runs only
         * when protection is enabled.
         *
         * @param enabled reports whether protection is currently enabled
         * @param evaluate evaluates the resolved execution context
         * @return this builder
         */
        public Builder protection(
                BooleanSupplier enabled,
                Function<GatewayToolExecutionContext, McpAbuseProtectionDecision> evaluate
        ) {
            BooleanSupplier requiredEnabled = Objects.requireNonNull(enabled, "enabled must not be null");
            Function<GatewayToolExecutionContext, McpAbuseProtectionDecision> requiredEvaluate =
                    Objects.requireNonNull(evaluate, "evaluate must not be null");
            return protectionEvaluator(new McpGatewayAbuseProtectionEvaluator() {
                @Override
                public boolean enabled() {
                    return requiredEnabled.getAsBoolean();
                }

                @Override
                public McpAbuseProtectionDecision evaluate(GatewayToolExecutionContext context) {
                    return requiredEvaluate.apply(context);
                }
            });
        }

        /**
         * Uses a custom Spring Security granted-scope extractor.
         *
         * @param extractor granted-scope extractor
         * @return this builder
         */
        public Builder grantedScopesExtractor(McpGrantedScopesExtractor extractor) {
            this.grantedScopesExtractor = Objects.requireNonNull(extractor, "extractor must not be null");
            return this;
        }

        /**
         * Uses a custom authorization observer.
         *
         * @param observer authorization observer
         * @return this builder
         */
        public Builder authorizationObserver(McpAuthorizationObserver observer) {
            this.authorizationObserver = Objects.requireNonNull(observer, "observer must not be null");
            return this;
        }

        /**
         * Uses a custom protection-rejection observer.
         *
         * @param observer protection-rejection observer
         * @return this builder
         */
        public Builder protectionRejectionObserver(McpProtectionRejectionObserver observer) {
            this.rejectionObserver = Objects.requireNonNull(observer, "observer must not be null");
            return this;
        }

        /**
         * Uses a custom fallback correlation-id resolver.
         *
         * @param resolver correlation-id resolver
         * @return this builder
         */
        public Builder correlationIdResolver(McpGatewayCorrelationIdResolver resolver) {
            this.correlationIdResolver = Objects.requireNonNull(resolver, "resolver must not be null");
            return this;
        }

        /**
         * Uses a custom invalid-request observer.
         *
         * @param observer invalid-request observer
         * @return this builder
         */
        public Builder invalidRequestObserver(McpInvalidRequestObserver observer) {
            this.invalidRequestObserver = Objects.requireNonNull(observer, "observer must not be null");
            return this;
        }

        /**
         * Builds a filter with the configured collaborators.
         *
         * @return configured WebFlux governance filter
         * @throws IllegalStateException when neither authorization nor protection is configured
         */
        public McpGatewayWebFluxGovernanceFilter build() {
            if (authorizationEvaluator == null && protectionEvaluator == null) {
                throw new IllegalStateException("authorization or protection must be configured");
            }
            return new McpGatewayWebFluxGovernanceFilter(
                    jsonMapper,
                    properties,
                    authorizationEvaluator,
                    protectionEvaluator,
                    contextResolver,
                    grantedScopesExtractor,
                    authorizationObserver,
                    rejectionObserver,
                    correlationIdResolver,
                    invalidRequestObserver
            );
        }
    }

    /**
     * Creates a filter with default scope extraction and no-op observations.
     * <p>
     * This constructor preserves the public-preview default behavior: Spring
     * Security {@code SCOPE_} authorities are used as granted scopes, correlation
     * IDs come from {@code X-Correlation-Id} with request-id fallback, and all
     * observers are no-ops.
     *
     * @param jsonMapper JSON mapper used for parsing and rejection responses
     * @param properties WebFlux adapter properties
     * @param authorizationEvaluator authorization evaluator backed by core contracts
     * @param protectionEvaluator protection evaluator backed by core contracts
     * @param contextResolver resolver that maps the request into core execution context
     */
    public McpGatewayWebFluxGovernanceFilter(JsonMapper jsonMapper,
                                             McpGatewayWebFluxProperties properties,
                                             McpGatewayAuthorizationEvaluator authorizationEvaluator,
                                             McpGatewayAbuseProtectionEvaluator protectionEvaluator,
                                             McpGatewayWebFluxContextResolver contextResolver) {
        this(
                jsonMapper,
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
     * @param jsonMapper JSON mapper used for parsing and rejection responses
     * @param properties WebFlux adapter properties
     * @param authorizationEvaluator authorization evaluator backed by core contracts
     * @param protectionEvaluator protection evaluator backed by core contracts
     * @param contextResolver resolver that maps the request into core execution context
     * @param grantedScopesExtractor extracts granted scopes from Spring Security authentication
     * @param authorizationObserver receives authorization observations
     * @param rejectionObserver receives protection rejection observations
     * @param correlationIdResolver resolves correlation IDs for fallback responses
     */
    public McpGatewayWebFluxGovernanceFilter(JsonMapper jsonMapper,
                                             McpGatewayWebFluxProperties properties,
                                             McpGatewayAuthorizationEvaluator authorizationEvaluator,
                                             McpGatewayAbuseProtectionEvaluator protectionEvaluator,
                                             McpGatewayWebFluxContextResolver contextResolver,
                                             McpGrantedScopesExtractor grantedScopesExtractor,
                                             McpAuthorizationObserver authorizationObserver,
                                             McpProtectionRejectionObserver rejectionObserver,
                                             McpGatewayCorrelationIdResolver correlationIdResolver) {
        this(
                jsonMapper,
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
     * {@code jsonMapper} and {@code contextResolver}, which are required.
     * The invalid-request observer receives only stable reason, server request id,
     * and correlation id fields; request payloads are never exposed to it.
     *
     * @param jsonMapper JSON mapper used for parsing and rejection responses
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
    public McpGatewayWebFluxGovernanceFilter(JsonMapper jsonMapper,
                                             McpGatewayWebFluxProperties properties,
                                             McpGatewayAuthorizationEvaluator authorizationEvaluator,
                                             McpGatewayAbuseProtectionEvaluator protectionEvaluator,
                                             McpGatewayWebFluxContextResolver contextResolver,
                                             McpGrantedScopesExtractor grantedScopesExtractor,
                                             McpAuthorizationObserver authorizationObserver,
                                             McpProtectionRejectionObserver rejectionObserver,
                                             McpGatewayCorrelationIdResolver correlationIdResolver,
                                             McpInvalidRequestObserver invalidRequestObserver) {
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper must not be null");
        this.parser = new McpJsonRpcToolInvocationParser(jsonMapper);
        this.properties = properties == null ? McpGatewayWebFluxProperties.defaults() : properties;
        this.mcpEndpointPath = PathContainer.parsePath(this.properties.mcpEndpoint());
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
        return Mono.defer(() -> filterRequest(exchange, chain));
    }

    private Mono<Void> filterRequest(ServerWebExchange exchange, WebFilterChain chain) {
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
        Mono<byte[]> cachedBody = McpGatewayWebFluxRequestBodies.read(exchange, properties.maxBodyBytes())
                .onErrorResume(
                        DataBufferLimitException.class,
                        ignored -> writePayloadTooLarge(exchange).then(Mono.<byte[]>empty())
                );

        return cachedBody
                .flatMap(bodyBytes -> {
                    McpJsonRpcMessageClassification classification = parser.classify(bodyBytes);
                    if (!classification.valid()) {
                        return writeInvalidRequest(exchange, classification.rejectionReason());
                    }
                    if (classification.response()) {
                        return chain.filter(McpGatewayWebFluxRequestBodies.decorate(exchange, bodyBytes));
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
                });
    }

    private Mono<Void> evaluate(ServerWebExchange exchange,
                                WebFilterChain chain,
                                Authentication authentication,
                                byte[] bodyBytes,
                                McpToolInvocation invocation) {
        GatewayToolExecutionContext context = contextResolver.resolve(authentication, exchange, invocation);
        List<String> extractedScopes = grantedScopesExtractor.extract(authentication);
        List<String> grantedScopes = extractedScopes == null ? List.of() : extractedScopes;
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
                    jsonMapper,
                    decision.protectionDecision(),
                    correlationId(exchange, context)
            );
        }
        return McpGatewayWebFluxResponses.forbidden(
                exchange,
                jsonMapper,
                decision.authorizationDecision(),
                decision.reason().code(),
                correlationId(exchange, context)
        );
    }

    private String correlationId(ServerWebExchange exchange, GatewayToolExecutionContext context) {
        return context.correlationId() == null
                ? correlationIdResolver.resolve(exchange)
                : context.correlationId();
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
                && sameRoutePath(
                        mcpEndpointPath,
                        exchange.getRequest().getPath().pathWithinApplication()
                );
    }

    private boolean sameRoutePath(PathContainer configuredPath, PathContainer requestPath) {
        List<PathContainer.Element> configuredElements = configuredPath.elements();
        List<PathContainer.Element> requestElements = requestPath.elements();
        if (configuredElements.size() != requestElements.size()) {
            return false;
        }

        for (int index = 0; index < configuredElements.size(); index++) {
            PathContainer.Element configured = configuredElements.get(index);
            PathContainer.Element request = requestElements.get(index);
            if (configured instanceof PathContainer.PathSegment configuredSegment
                    && request instanceof PathContainer.PathSegment requestSegment) {
                if (!configuredSegment.valueToMatch().equals(requestSegment.valueToMatch())) {
                    return false;
                }
            } else if (configured instanceof PathContainer.Separator
                    && request instanceof PathContainer.Separator) {
                if (!configured.value().equals(request.value())) {
                    return false;
                }
            } else {
                return false;
            }
        }
        return true;
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
                jsonMapper,
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
                jsonMapper,
                reason.code(),
                correlationId
        );
    }

    @Override
    public int getOrder() {
        return properties.governanceFilterOrder();
    }
}
