package mcp.gateway.spring.webflux;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import mcp.gateway.core.authz.McpToolAccessRegistry;
import mcp.gateway.core.authz.McpToolAccessRule;
import mcp.gateway.core.authz.McpToolAuthorizer;
import mcp.gateway.core.context.GatewayToolExecutionContext;
import mcp.gateway.core.tool.McpToolSurface;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class McpGatewayWebFluxActiveCatalogTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final McpToolAccessRegistry ACCESS = McpToolAccessRegistry.of(List.of(
            McpToolAccessRule.of("files.read", McpToolSurface.GUIDED, List.of("files:read")),
            McpToolAccessRule.of("files.write", McpToolSurface.EXPERT, List.of("files:write"))
    ));
    private final List<String> calls = new ArrayList<>();
    private final List<McpAuthorizationObservation> observations = new ArrayList<>();
    private final AtomicReference<String> downstreamBody = new AtomicReference<>();

    @ParameterizedTest
    @ValueSource(strings = {"files.write", "unknown_tool"})
    void inactiveAndUnknownToolsRejectBeforeAuthorizationEvenWhenCallerHasThePolicyScope(String toolName) {
        ServerWebExchange exchange = exchange(toolCall(toolName), "files:write");

        StepVerifier.create(filter().filter(exchange, this::downstream)).verifyComplete();

        assertEquals(HttpStatus.OK, exchange.getResponse().getStatusCode());
        assertEquals(MediaType.APPLICATION_JSON, exchange.getResponse().getHeaders().getContentType());
        assertFalse(exchange.getResponse().getHeaders().containsHeader(HttpHeaders.WWW_AUTHENTICATE));
        assertEquals(JSON.readTree("""
                {"jsonrpc":"2.0","id":"catalog-id","error":{"code":-32602,"message":"Unknown tool"}}
                """), JSON.readTree(responseBody(exchange)));
        assertEquals(List.of(), calls);
        assertEquals(List.of(), observations);
        assertNull(downstreamBody.get());
    }

    @Test
    void exposedToolWithItsRequiredScopeReachesTheHandler() {
        String body = toolCall("files.read");
        ServerWebExchange exchange = exchange(body, "files:read");

        StepVerifier.create(filter().filter(exchange, this::downstream)).verifyComplete();

        assertEquals(HttpStatus.OK, exchange.getResponse().getStatusCode());
        assertEquals(List.of("principal", "context", "authorize", "downstream"), calls);
        assertEquals(body, downstreamBody.get());
        assertEquals(1, observations.size());
        assertEquals("allowed", observations.get(0).outcome());
    }

    @Test
    void exposedToolWithoutItsRequiredScopeGetsForbiddenAndNeverReachesTheHandler() {
        ServerWebExchange exchange = exchange(toolCall("files.read"), "files:write");

        StepVerifier.create(filter().filter(exchange, this::downstream)).verifyComplete();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        assertEquals("Bearer error=\"insufficient_scope\", scope=\"files:read\"",
                exchange.getResponse().getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE));
        assertEquals("insufficient_scope", JSON.readTree(responseBody(exchange)).get("error").asString());
        assertEquals(List.of("principal", "context", "authorize"), calls);
        assertEquals(1, observations.size());
        assertEquals("denied", observations.get(0).outcome());
        assertNull(downstreamBody.get());
    }

    private McpGatewayWebFluxGovernanceFilter filter() {
        McpToolAuthorizer authorizer = McpToolAuthorizer.of(ACCESS, List.of("mcp:tools:list"));
        return McpGatewayWebFluxGovernanceFilter.builder(JSON, (authentication, exchange, invocation) -> {
                    calls.add("context");
                    return GatewayToolExecutionContext.of(authentication.getName(), "workspace", "catalog-correlation",
                            invocation, null);
                })
                .toolRegistry(ACCESS.activeToolRegistry(List.of("files.read")))
                .authorization(() -> McpGatewayAuthorizationMode.ENFORCE, (scopes, context) -> {
                    calls.add("authorize");
                    return authorizer.authorize(scopes, context);
                })
                .authorizationObserver(observations::add)
                .build();
    }

    private ServerWebExchange exchange(String body, String... scopes) {
        return MockServerWebExchange.from(MockServerHttpRequest.post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON).body(body)).mutate()
                .principal(Mono.defer(() -> {
                    calls.add("principal");
                    return Mono.just(new UsernamePasswordAuthenticationToken("catalog-client", "unused",
                            Arrays.stream(scopes).map(scope -> new SimpleGrantedAuthority("SCOPE_" + scope)).toList()));
                })).build();
    }

    private Mono<Void> downstream(ServerWebExchange exchange) {
        calls.add("downstream");
        exchange.getResponse().setStatusCode(HttpStatus.OK);
        return DataBufferUtils.join(exchange.getRequest().getBody()).doOnNext(buffer -> {
            try {
                byte[] bytes = new byte[buffer.readableByteCount()];
                buffer.read(bytes);
                downstreamBody.set(new String(bytes, StandardCharsets.UTF_8));
            } finally {
                DataBufferUtils.release(buffer);
            }
        }).then(exchange.getResponse().setComplete());
    }

    private static String toolCall(String name) {
        return """
                {"jsonrpc":"2.0","id":"catalog-id","method":"tools/call",
                 "params":{"name":"%s","arguments":{"note":"original 雪"}}}
                """.formatted(name);
    }

    private static String responseBody(ServerWebExchange exchange) {
        return ((MockServerHttpResponse) exchange.getResponse()).getBodyAsString().block();
    }
}
