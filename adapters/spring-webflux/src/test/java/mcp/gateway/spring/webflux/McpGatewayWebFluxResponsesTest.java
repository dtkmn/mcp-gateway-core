package mcp.gateway.spring.webflux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import mcp.gateway.core.authz.ToolAuthorizationDecision;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class McpGatewayWebFluxResponsesTest {
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void rendersUnknownToolAsMinimalJsonRpcErrorWithExactRequestId() {
        for (String idJson : new String[]{
                "\"request-1\"",
                "\"\"",
                "\"quote\\\" slash\\\\ newline\\n unicode☃漢字\"",
                "0",
                "-42",
                "9007199254740993",
                "1234567890123456789012345678901234567890",
                "-1234567890123456789012345678901234567890"
        }) {
            MockServerWebExchange exchange = exchangeWithAuthChallenge();

            StepVerifier.create(McpGatewayWebFluxResponses.unknownTool(
                    exchange, jsonMapper, jsonMapper.readTree(idJson))).verifyComplete();

            assertJsonRpcError(exchange, HttpStatus.OK, idJson, -32602, "Unknown tool");
        }
    }

    @Test
    void rendersInternalToolFailureWithoutExposingConfigurationDetails() {
        MockServerWebExchange exchange = exchangeWithAuthChallenge();

        StepVerifier.create(McpGatewayWebFluxResponses.internalToolError(
                exchange, jsonMapper, jsonMapper.readTree("\"tool-request-1\""))).verifyComplete();

        assertJsonRpcError(exchange, HttpStatus.OK, "\"tool-request-1\"", -32603, "Internal error");
    }

    @Test
    void rendersInvalidToolCallIdWithNullIdAndNoAuthChallenge() {
        MockServerWebExchange exchange = exchangeWithAuthChallenge();

        StepVerifier.create(McpGatewayWebFluxResponses.invalidToolCallId(exchange, jsonMapper)).verifyComplete();

        assertJsonRpcError(exchange, HttpStatus.BAD_REQUEST, "null", -32600, "Invalid Request");
    }

    @Test
    void acceptsNotificationsWithoutAResponseBodyOrAuthChallenge() {
        MockServerWebExchange exchange = exchangeWithAuthChallenge();

        StepVerifier.create(McpGatewayWebFluxResponses.notificationAccepted(exchange)).verifyComplete();

        assertEquals(HttpStatus.ACCEPTED, exchange.getResponse().getStatusCode());
        assertFalse(exchange.getResponse().getHeaders().containsHeader(HttpHeaders.WWW_AUTHENTICATE));
        assertEquals("", exchange.getResponse().getBodyAsString().block());
    }

    @Test
    void serializationFailureDoesNotCommitAnIdLosingFallbackOrExposeExceptionDetails() {
        JsonMapper failingMapper = new JsonMapper() {
            @Override
            public byte[] writeValueAsBytes(Object body) {
                throw new IllegalStateException("sensitive configuration details");
            }
        };
        for (int response = 0; response < 3; response++) {
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/mcp").build());
            JsonNode requestId = jsonMapper.readTree("\"quote\\\"☃\"");
            var result = switch (response) {
                case 0 -> McpGatewayWebFluxResponses.unknownTool(exchange, failingMapper, requestId);
                case 1 -> McpGatewayWebFluxResponses.internalToolError(exchange, failingMapper, requestId);
                default -> McpGatewayWebFluxResponses.invalidToolCallId(exchange, failingMapper);
            };

            StepVerifier.create(result).expectErrorSatisfies(error -> {
                assertEquals(IllegalStateException.class, error.getClass());
                assertEquals("Unable to serialize MCP error response", error.getMessage());
                assertNull(error.getCause());
            }).verify();

            assertFalse(exchange.getResponse().isCommitted());
            assertNull(exchange.getResponse().getStatusCode());
            assertFalse(exchange.getResponse().getHeaders().containsHeader(HttpHeaders.WWW_AUTHENTICATE));
        }
    }

    @Test
    void preservesExistingForbiddenSerializationFallback() {
        JsonMapper failingMapper = new JsonMapper() {
            @Override
            public byte[] writeValueAsBytes(Object body) {
                throw new IllegalStateException("sensitive configuration details");
            }
        };
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/mcp").build());

        StepVerifier.create(McpGatewayWebFluxResponses.forbidden(
                exchange, failingMapper, denied(List.of("demo:read")), "insufficient_scope", "corr-1"
        )).verifyComplete();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        assertEquals("{\"error\":\"insufficient_scope\"}", exchange.getResponse().getBodyAsString().block());
    }

    @Test
    void rendersValidOAuthScopesInBearerChallenge() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/mcp").build());

        StepVerifier.create(McpGatewayWebFluxResponses.forbidden(
                exchange,
                JsonMapper.builder().build(),
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
                    JsonMapper.builder().build(),
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

    private MockServerWebExchange exchangeWithAuthChallenge() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/mcp").build());
        exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"insufficient_scope\"");
        return exchange;
    }

    private void assertJsonRpcError(MockServerWebExchange exchange,
                                   HttpStatus status,
                                   String idJson,
                                   int code,
                                   String message) {
        assertEquals(status, exchange.getResponse().getStatusCode());
        assertEquals(MediaType.APPLICATION_JSON, exchange.getResponse().getHeaders().getContentType());
        assertFalse(exchange.getResponse().getHeaders().containsHeader(HttpHeaders.WWW_AUTHENTICATE));
        JsonNode expected = jsonMapper.readTree("{\"jsonrpc\":\"2.0\",\"id\":" + idJson
                + ",\"error\":{\"code\":" + code + ",\"message\":\"" + message + "\"}}");
        assertEquals(expected, jsonMapper.readTree(exchange.getResponse().getBodyAsString().block()));
    }
}
