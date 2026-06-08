package mcp.gateway.spring.webflux;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import mcp.gateway.core.context.GatewayToolExecutionContext;
import mcp.gateway.core.protection.McpAbuseProtectionDecision;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpGatewayWebFluxAbuseProtectionFilterTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void allowsRequestAndPreservesBodyForDownstreamHandler() {
        AtomicReference<String> downstreamBody = new AtomicReference<>();
        McpGatewayWebFluxAbuseProtectionFilter filter = new McpGatewayWebFluxAbuseProtectionFilter(
                OBJECT_MAPPER,
                McpGatewayWebFluxProperties.defaults(),
                evaluator(McpAbuseProtectionDecision.allow("demo_tool", "demo-client", "demo-workspace")),
                contextResolver()
        );
        String body = toolCallBody();

        StepVerifier.create(filter.filter(exchange(body), captureBodyChain(downstreamBody))).verifyComplete();

        assertEquals(body, downstreamBody.get());
    }

    @Test
    void rejectsRequestWithRetryAfterAndSafeJsonResponse() {
        List<McpAbuseProtectionDecision> rejected = new ArrayList<>();
        McpGatewayWebFluxAbuseProtectionFilter filter = new McpGatewayWebFluxAbuseProtectionFilter(
                OBJECT_MAPPER,
                McpGatewayWebFluxProperties.defaults(),
                evaluator(McpAbuseProtectionDecision.reject(
                        "rate_limited",
                        "too many requests",
                        "demo_tool",
                        "demo-client",
                        "demo-workspace",
                        7
                )),
                contextResolver(),
                (decision, context) -> rejected.add(decision),
                McpGatewayCorrelationIdResolver.defaultResolver()
        );
        ServerWebExchange exchange = exchange(toolCallBody());

        StepVerifier.create(filter.filter(exchange, ignored -> Mono.error(new AssertionError("must not call chain"))))
                .verifyComplete();

        assertEquals(1, rejected.size());
        assertEquals(429, exchange.getResponse().getStatusCode().value());
        assertEquals("7", exchange.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
        assertEquals(MediaType.APPLICATION_JSON, exchange.getResponse().getHeaders().getContentType());
        assertTrue(responseBody(exchange).contains("\"error\":\"rate_limited\""));
        assertTrue(responseBody(exchange).contains("\"tool\":\"demo_tool\""));
        assertTrue(responseBody(exchange).contains("\"correlationId\":\"corr-1\""));
    }

    @Test
    void rejectsRequestWithAnonymousPrincipalInsteadOfBypassingProtection() {
        AtomicBoolean downstreamCalled = new AtomicBoolean(false);
        AtomicReference<GatewayToolExecutionContext> context = new AtomicReference<>();
        McpGatewayAbuseProtectionEvaluator evaluator = new McpGatewayAbuseProtectionEvaluator() {
            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public McpAbuseProtectionDecision evaluate(GatewayToolExecutionContext requestContext) {
                context.set(requestContext);
                return McpAbuseProtectionDecision.reject(
                        "rate_limited",
                        "too many requests",
                        "demo_tool",
                        null,
                        "demo-workspace",
                        3
                );
            }
        };
        McpGatewayWebFluxAbuseProtectionFilter filter = new McpGatewayWebFluxAbuseProtectionFilter(
                OBJECT_MAPPER,
                McpGatewayWebFluxProperties.defaults(),
                evaluator,
                contextResolver()
        );
        ServerWebExchange exchange = exchangeWithoutPrincipal(toolCallBody());

        StepVerifier.create(filter.filter(exchange, ignored -> {
            downstreamCalled.set(true);
            return Mono.empty();
        })).verifyComplete();

        assertFalse(downstreamCalled.get());
        assertEquals(429, exchange.getResponse().getStatusCode().value());
        assertEquals("anonymous", context.get().principalId());
        assertTrue(responseBody(exchange).contains("\"error\":\"rate_limited\""));
    }

    private static McpGatewayAbuseProtectionEvaluator evaluator(McpAbuseProtectionDecision decision) {
        return new McpGatewayAbuseProtectionEvaluator() {
            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public McpAbuseProtectionDecision evaluate(GatewayToolExecutionContext context) {
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

    private static ServerWebExchange exchange(String body) {
        Authentication authentication = new UsernamePasswordAuthenticationToken("demo-client", "n/a");
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

    private static String toolCallBody() {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"demo_tool\"}}";
    }

    private static String responseBody(ServerWebExchange exchange) {
        return ((MockServerHttpResponse) exchange.getResponse()).getBodyAsString().block();
    }
}
