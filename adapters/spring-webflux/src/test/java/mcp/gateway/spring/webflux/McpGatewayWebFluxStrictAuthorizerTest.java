package mcp.gateway.spring.webflux;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import mcp.gateway.core.authz.McpToolAccessRegistry;
import mcp.gateway.core.authz.McpToolAccessRule;
import mcp.gateway.core.authz.McpToolAuthorizer;
import mcp.gateway.core.context.GatewayToolExecutionContext;
import mcp.gateway.core.protection.McpAbuseProtectionDecision;
import mcp.gateway.core.tool.McpToolSurface;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class McpGatewayWebFluxStrictAuthorizerTest {
    private static final String BODY = """
            {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"files.read"}}
            """;
    private final McpToolAuthorizer authorizer = McpToolAuthorizer.of(
            McpToolAccessRegistry.of(List.of(
                    McpToolAccessRule.of("files.read", McpToolSurface.GUIDED, List.of("files:read"))
            )),
            List.of("mcp:tools:list")
    );
    private final List<McpAuthorizationObservation> observations = new ArrayList<>();
    private final AtomicInteger downstreamCalls = new AtomicInteger();
    private final AtomicReference<String> downstreamBody = new AtomicReference<>();

    @Test
    void strictMethodReferenceAllowsMappedToolWithItsRequiredScope() {
        McpGatewayWebFluxGovernanceFilter filter = builder(McpGatewayAuthorizationMode.ENFORCE).build();
        ServerWebExchange exchange = exchange("files:read");

        StepVerifier.create(filter.filter(exchange, this::downstream)).verifyComplete();

        assertEquals(HttpStatus.OK, exchange.getResponse().getStatusCode());
        assertEquals(1, downstreamCalls.get());
        assertEquals(BODY, downstreamBody.get());
        assertObservation("allowed", "scope_granted", List.of("files:read"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "*"})
    void strictMethodReferenceRejectsMissingScopeAndWildcardAloneInEnforceMode(String grantedScope) {
        McpGatewayWebFluxGovernanceFilter filter = builder(McpGatewayAuthorizationMode.ENFORCE).build();
        ServerWebExchange exchange = exchange(grantedScope);

        StepVerifier.create(filter.filter(exchange, this::downstream)).verifyComplete();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        assertEquals("Bearer error=\"insufficient_scope\", scope=\"files:read\"",
                exchange.getResponse().getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE));
        assertEquals(0, downstreamCalls.get());
        assertNull(downstreamBody.get());
        assertObservation("denied", "insufficient_scope",
                grantedScope.isEmpty() ? List.of() : List.of(grantedScope));
    }

    @Test
    void warnModeObservesStrictDenialAndStillForwardsTheRequest() {
        McpGatewayWebFluxGovernanceFilter filter = builder(McpGatewayAuthorizationMode.WARN).build();
        ServerWebExchange exchange = exchange();

        StepVerifier.create(filter.filter(exchange, this::downstream)).verifyComplete();

        assertEquals(HttpStatus.OK, exchange.getResponse().getStatusCode());
        assertEquals(1, downstreamCalls.get());
        assertEquals(BODY, downstreamBody.get());
        assertObservation("warn", "insufficient_scope", List.of());
    }

    @Test
    void warnModeDoesNotOverrideProtectionRejection() {
        AtomicInteger protectionCalls = new AtomicInteger();
        List<McpAbuseProtectionDecision> rejections = new ArrayList<>();
        McpGatewayWebFluxGovernanceFilter filter = builder(McpGatewayAuthorizationMode.WARN)
                .protection(() -> true, context -> {
                    protectionCalls.incrementAndGet();
                    return protectionRejection(context);
                })
                .protectionRejectionObserver((decision, context) -> rejections.add(decision))
                .build();
        ServerWebExchange exchange = exchange();

        StepVerifier.create(filter.filter(exchange, this::downstream)).verifyComplete();

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exchange.getResponse().getStatusCode());
        assertEquals("7", exchange.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
        assertEquals(1, protectionCalls.get());
        assertEquals(1, rejections.size());
        assertEquals(0, downstreamCalls.get());
        assertNull(downstreamBody.get());
        assertObservation("warn", "insufficient_scope", List.of());
    }

    @Test
    void disabledModeSkipsStrictEvaluationWhileProtectionCanStillReject() {
        AtomicInteger authorizationCalls = new AtomicInteger();
        AtomicInteger protectionCalls = new AtomicInteger();
        List<McpAbuseProtectionDecision> rejections = new ArrayList<>();
        McpGatewayWebFluxGovernanceFilter filter = builder(McpGatewayAuthorizationMode.DISABLED)
                .authorization(() -> McpGatewayAuthorizationMode.DISABLED, (scopes, context) -> {
                    authorizationCalls.incrementAndGet();
                    return authorizer.authorize(scopes, context);
                })
                .protection(() -> true, context -> {
                    protectionCalls.incrementAndGet();
                    return protectionRejection(context);
                })
                .protectionRejectionObserver((decision, context) -> rejections.add(decision))
                .build();
        ServerWebExchange exchange = exchange();

        StepVerifier.create(filter.filter(exchange, this::downstream)).verifyComplete();

        assertEquals(0, authorizationCalls.get());
        assertEquals(List.of(), observations);
        assertEquals(1, protectionCalls.get());
        assertEquals(1, rejections.size());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exchange.getResponse().getStatusCode());
        assertEquals("7", exchange.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
        assertEquals(0, downstreamCalls.get());
        assertNull(downstreamBody.get());
    }

    private McpGatewayWebFluxGovernanceFilter.Builder builder(McpGatewayAuthorizationMode mode) {
        return McpGatewayWebFluxGovernanceFilter.builder(
                        JsonMapper.builder().build(),
                        (authentication, exchange, invocation) -> GatewayToolExecutionContext.of(
                                authentication == null ? null : authentication.getName(),
                                "test-workspace",
                                "strict-authorizer-test",
                                invocation,
                                null
                        )
                )
                .authorization(() -> mode, authorizer::authorize)
                .authorizationObserver(observations::add);
    }

    private ServerWebExchange exchange(String... grantedScopes) {
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                "test-client",
                "unused",
                Arrays.stream(grantedScopes)
                        .filter(scope -> !scope.isBlank())
                        .map(scope -> new SimpleGrantedAuthority("SCOPE_" + scope))
                        .toList()
        );
        return MockServerWebExchange.from(MockServerHttpRequest.post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(BODY))
                .mutate()
                .principal(Mono.just(authentication))
                .build();
    }

    private Mono<Void> downstream(ServerWebExchange exchange) {
        downstreamCalls.incrementAndGet();
        exchange.getResponse().setStatusCode(HttpStatus.OK);
        return DataBufferUtils.join(exchange.getRequest().getBody())
                .doOnNext(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    DataBufferUtils.release(buffer);
                    downstreamBody.set(new String(bytes, StandardCharsets.UTF_8));
                })
                .then(exchange.getResponse().setComplete());
    }

    private McpAbuseProtectionDecision protectionRejection(GatewayToolExecutionContext context) {
        return McpAbuseProtectionDecision.reject(
                "rate_limited", "Too many requests", context.toolName(),
                context.principalId(), context.workspaceId(), 7
        );
    }

    private void assertObservation(String outcome, String reason, List<String> grantedScopes) {
        assertEquals(1, observations.size());
        McpAuthorizationObservation observation = observations.get(0);
        assertEquals("files.read", observation.actionName());
        assertEquals(outcome, observation.outcome());
        assertEquals(reason, observation.reason());
        assertEquals(List.of("files:read"), observation.requiredScopes());
        assertEquals(grantedScopes, observation.grantedScopes());
        assertEquals("test-client", observation.context().principalId());
        assertEquals("test-workspace", observation.context().workspaceId());
    }
}
