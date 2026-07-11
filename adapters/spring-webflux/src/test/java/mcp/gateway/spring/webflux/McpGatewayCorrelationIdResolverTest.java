package mcp.gateway.spring.webflux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

class McpGatewayCorrelationIdResolverTest {

    @Test
    void returnsTrimmedSafeCallerCorrelationId() {
        ServerWebExchange exchange = exchangeWithHeader("X-Correlation-Id", "  tenant/request-1  ");

        assertEquals("tenant/request-1", McpGatewayCorrelationIdResolver.defaultResolver().resolve(exchange));
    }

    @Test
    void rejectsUnsafeOrOversizedCallerValuesAndFallsBackToServerRequestId() {
        for (String value : new String[]{"bad value", "bad\tvalue", "a".repeat(129)}) {
            ServerWebExchange exchange = exchangeWithHeader("X-Correlation-Id", value);

            assertEquals(
                    exchange.getRequest().getId(),
                    McpGatewayCorrelationIdResolver.defaultResolver().resolve(exchange)
            );
        }
    }

    @Test
    void customHeaderResolverUsesTheSameValidationAndNullExchangeIsSafe() {
        ServerWebExchange exchange = exchangeWithHeader("X-Trace-Id", "trace_1");

        assertEquals("trace_1", McpGatewayCorrelationIdResolver.fromHeader(" X-Trace-Id ").resolve(exchange));
        assertNull(McpGatewayCorrelationIdResolver.defaultResolver().resolve(null));
    }

    private static ServerWebExchange exchangeWithHeader(String name, String value) {
        return MockServerWebExchange.from(MockServerHttpRequest.post("/mcp").header(name, value).build());
    }
}
