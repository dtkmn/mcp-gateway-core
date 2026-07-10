package mcp.gateway.spring.webflux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class McpGatewayWebFluxRequestBodiesTest {

    @Test
    void replayedBodyUsesOneConsistentFramingHeader() {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/mcp")
                .header(HttpHeaders.CONTENT_LENGTH, "999")
                .header(HttpHeaders.TRANSFER_ENCODING, "chunked")
                .body("original"));
        byte[] replayedBody = "replayed".getBytes(StandardCharsets.UTF_8);

        ServerWebExchange decorated = McpGatewayWebFluxRequestBodies.decorate(exchange, replayedBody);

        assertEquals(replayedBody.length, decorated.getRequest().getHeaders().getContentLength());
        assertFalse(decorated.getRequest().getHeaders().containsHeader(HttpHeaders.TRANSFER_ENCODING));
        StepVerifier.create(Mono.from(decorated.getRequest().getBody()))
                .assertNext(buffer -> {
                    try {
                        byte[] bytes = new byte[buffer.readableByteCount()];
                        buffer.read(bytes);
                        assertEquals("replayed", new String(bytes, StandardCharsets.UTF_8));
                    } finally {
                        DataBufferUtils.release(buffer);
                    }
                })
                .verifyComplete();
    }
}
