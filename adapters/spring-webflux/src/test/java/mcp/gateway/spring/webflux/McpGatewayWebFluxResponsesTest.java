package mcp.gateway.spring.webflux;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import mcp.gateway.core.authz.ToolAuthorizationDecision;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.test.StepVerifier;

class McpGatewayWebFluxResponsesTest {

    @Test
    void rendersValidOAuthScopesInBearerChallenge() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/mcp").build());

        StepVerifier.create(McpGatewayWebFluxResponses.forbidden(
                exchange,
                new ObjectMapper(),
                denied(List.of("demo:read", "workspace/tool.run")),
                "insufficient_scope",
                "corr-1"
        )).verifyComplete();

        assertEquals(
                "Bearer error=\"insufficient_scope\", scope=\"demo:read workspace/tool.run\"",
                exchange.getResponse().getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)
        );
    }

    @Test
    void omitsScopeParameterWhenAnyScopeIsNotAnRfc6749ScopeToken() {
        for (String unsafeScope : List.of(
                "quoted\"scope",
                "back\\slash",
                "two words",
                "line\r\nbreak",
                "non-ascii-☃"
        )) {
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/mcp").build());

            StepVerifier.create(McpGatewayWebFluxResponses.forbidden(
                    exchange,
                    new ObjectMapper(),
                    denied(List.of("safe", unsafeScope)),
                    "insufficient_scope",
                    "corr-1"
            )).verifyComplete();

            assertEquals(
                    "Bearer error=\"insufficient_scope\"",
                    exchange.getResponse().getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)
            );
        }
    }

    private static ToolAuthorizationDecision denied(List<String> requiredScopes) {
        return new ToolAuthorizationDecision(
                false,
                true,
                "demo_tool",
                requiredScopes,
                List.of(),
                requiredScopes
        );
    }
}
