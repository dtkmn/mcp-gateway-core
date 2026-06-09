package mcp.gateway.spring.webflux;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import mcp.gateway.core.authz.ToolAuthorizationDecision;
import mcp.gateway.core.context.GatewayToolExecutionContext;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpGatewayWebFluxAuthorizationFilterTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final McpGatewayWebFluxProperties PROPERTIES =
            new McpGatewayWebFluxProperties("/mcp", 4096, 10, 11);

    @Test
    void allowsMappedRequestAndPreservesBodyForDownstreamHandler() {
        List<McpAuthorizationObservation> observations = new ArrayList<>();
        AtomicReference<String> downstreamBody = new AtomicReference<>();
        McpGatewayWebFluxAuthorizationFilter filter = new McpGatewayWebFluxAuthorizationFilter(
                OBJECT_MAPPER,
                PROPERTIES,
                evaluator(McpGatewayAuthorizationMode.ENFORCE, new ToolAuthorizationDecision(
                        true,
                        true,
                        "demo_tool",
                        List.of("demo:run"),
                        List.of("demo:run"),
                        List.of()
                )),
                contextResolver(),
                McpGrantedScopesExtractor.springSecurityScopes(),
                observations::add,
                McpGatewayCorrelationIdResolver.defaultResolver()
        );
        String body = toolCallBody();
        ServerWebExchange exchange = exchange(body, authentication("demo-client", "SCOPE_demo:run"));

        StepVerifier.create(filter.filter(exchange, captureBodyChain(downstreamBody))).verifyComplete();

        assertEquals(body, downstreamBody.get());
        assertEquals(1, observations.size());
        assertEquals("allowed", observations.get(0).outcome());
        assertEquals("scope_granted", observations.get(0).reason());
        assertEquals("demo-client", observations.get(0).context().principalId());
        assertEquals("corr-1", observations.get(0).context().correlationId());
    }

    @Test
    void deniesMappedRequestWithSafeJsonResponse() {
        AtomicBoolean downstreamCalled = new AtomicBoolean(false);
        McpGatewayWebFluxAuthorizationFilter filter = new McpGatewayWebFluxAuthorizationFilter(
                OBJECT_MAPPER,
                PROPERTIES,
                evaluator(McpGatewayAuthorizationMode.ENFORCE, new ToolAuthorizationDecision(
                        false,
                        true,
                        "demo_tool",
                        List.of("demo:run"),
                        List.of("demo:read"),
                        List.of("demo:run")
                )),
                contextResolver()
        );
        ServerWebExchange exchange = exchange(toolCallBody(), authentication("demo-client", "SCOPE_demo:read"));

        StepVerifier.create(filter.filter(exchange, markCalledChain(downstreamCalled))).verifyComplete();

        assertFalse(downstreamCalled.get());
        assertEquals(403, exchange.getResponse().getStatusCode().value());
        assertEquals(MediaType.APPLICATION_JSON, exchange.getResponse().getHeaders().getContentType());
        assertTrue(exchange.getResponse().getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE).contains("demo:run"));
        assertTrue(responseBody(exchange).contains("\"error\":\"insufficient_scope\""));
        assertTrue(responseBody(exchange).contains("\"tool\":\"demo_tool\""));
        assertTrue(responseBody(exchange).contains("\"correlationId\":\"corr-1\""));
    }

    @Test
    void deniesMappedRequestWithAnonymousPrincipalInsteadOfBypassingAuthorization() {
        AtomicBoolean downstreamCalled = new AtomicBoolean(false);
        AtomicReference<Collection<String>> grantedScopes = new AtomicReference<>();
        AtomicReference<GatewayToolExecutionContext> context = new AtomicReference<>();
        McpGatewayAuthorizationEvaluator evaluator = new McpGatewayAuthorizationEvaluator() {
            @Override
            public McpGatewayAuthorizationMode mode() {
                return McpGatewayAuthorizationMode.ENFORCE;
            }

            @Override
            public ToolAuthorizationDecision authorize(Collection<String> scopes, GatewayToolExecutionContext requestContext) {
                grantedScopes.set(scopes);
                context.set(requestContext);
                return new ToolAuthorizationDecision(
                        false,
                        true,
                        "demo_tool",
                        List.of("demo:run"),
                        List.of(),
                        List.of("demo:run")
                );
            }
        };
        McpGatewayWebFluxAuthorizationFilter filter = new McpGatewayWebFluxAuthorizationFilter(
                OBJECT_MAPPER,
                PROPERTIES,
                evaluator,
                contextResolver()
        );
        ServerWebExchange exchange = exchangeWithoutPrincipal(toolCallBody());

        StepVerifier.create(filter.filter(exchange, markCalledChain(downstreamCalled))).verifyComplete();

        assertFalse(downstreamCalled.get());
        assertEquals(403, exchange.getResponse().getStatusCode().value());
        assertTrue(grantedScopes.get().isEmpty());
        assertEquals("anonymous", context.get().principalId());
    }

    @Test
    void rejectsOversizedBodyBeforeDownstreamHandler() {
        AtomicBoolean downstreamCalled = new AtomicBoolean(false);
        McpGatewayWebFluxAuthorizationFilter filter = new McpGatewayWebFluxAuthorizationFilter(
                OBJECT_MAPPER,
                new McpGatewayWebFluxProperties("/mcp", 1024, 10, 11),
                evaluator(McpGatewayAuthorizationMode.ENFORCE, new ToolAuthorizationDecision(
                        true,
                        true,
                        "demo_tool",
                        List.of("demo:run"),
                        List.of("demo:run"),
                        List.of()
                )),
                contextResolver()
        );
        ServerWebExchange exchange = exchange(toolCallBody(), authentication("demo-client", "SCOPE_demo:run"));
        exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/mcp")
                .header("X-Correlation-Id", "corr-1")
                .header(HttpHeaders.CONTENT_LENGTH, "4096")
                .contentType(MediaType.APPLICATION_JSON)
                .body(toolCallBody()))
                .mutate()
                .principal(Mono.just(authentication("demo-client", "SCOPE_demo:run")))
                .build();

        StepVerifier.create(filter.filter(exchange, markCalledChain(downstreamCalled))).verifyComplete();

        assertFalse(downstreamCalled.get());
        assertEquals(413, exchange.getResponse().getStatusCode().value());
        assertTrue(responseBody(exchange).contains("\"error\":\"request_body_too_large\""));
    }

    private static McpGatewayAuthorizationEvaluator evaluator(McpGatewayAuthorizationMode mode,
                                                             ToolAuthorizationDecision decision) {
        return new McpGatewayAuthorizationEvaluator() {
            @Override
            public McpGatewayAuthorizationMode mode() {
                return mode;
            }

            @Override
            public ToolAuthorizationDecision authorize(Collection<String> grantedScopes,
                                                       GatewayToolExecutionContext context) {
                return decision;
            }
        };
    }

    private static McpGatewayWebFluxContextResolver contextResolver() {
        return (authentication, exchange, invocation) -> GatewayToolExecutionContext.of(
                authentication == null ? null : authentication.getName(),
                "demo-workspace",
                exchange.getRequest().getHeaders().getFirst("X-Correlation-Id"),
                invocation,
                null
        );
    }

    private static WebFilterChain captureBodyChain(AtomicReference<String> downstreamBody) {
        return exchange -> DataBufferUtils.join(exchange.getRequest().getBody())
                .doOnNext(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    DataBufferUtils.release(buffer);
                    downstreamBody.set(new String(bytes, StandardCharsets.UTF_8));
                })
                .then();
    }

    private static WebFilterChain markCalledChain(AtomicBoolean called) {
        return exchange -> {
            called.set(true);
            return Mono.empty();
        };
    }

    private static ServerWebExchange exchange(String body, Authentication authentication) {
        return MockServerWebExchange.from(MockServerHttpRequest.post("/mcp")
                        .header("X-Correlation-Id", "corr-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body))
                .mutate()
                .principal(Mono.just(authentication))
                .build();
    }

    private static ServerWebExchange exchangeWithoutPrincipal(String body) {
        return MockServerWebExchange.from(MockServerHttpRequest.post("/mcp")
                .header("X-Correlation-Id", "corr-1")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body));
    }

    private static Authentication authentication(String name, String authority) {
        return new UsernamePasswordAuthenticationToken(name, "n/a", List.of(new SimpleGrantedAuthority(authority)));
    }

    private static String toolCallBody() {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"demo_tool\"}}";
    }

    private static String responseBody(ServerWebExchange exchange) {
        return ((MockServerHttpResponse) exchange.getResponse()).getBodyAsString().block();
    }
}
