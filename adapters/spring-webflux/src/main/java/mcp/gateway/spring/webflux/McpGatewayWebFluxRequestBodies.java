package mcp.gateway.spring.webflux;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

final class McpGatewayWebFluxRequestBodies {
    private McpGatewayWebFluxRequestBodies() {
    }

    static boolean contentLengthExceedsLimit(ServerWebExchange exchange, int maxBodyBytes) {
        long contentLength = exchange.getRequest().getHeaders().getContentLength();
        return contentLength > maxBodyBytes;
    }

    static Mono<byte[]> read(ServerWebExchange exchange, int maxBodyBytes) {
        return DataBufferUtils.join(exchange.getRequest().getBody(), maxBodyBytes)
                .defaultIfEmpty(exchange.getResponse().bufferFactory().wrap(new byte[0]))
                .map(dataBuffer -> {
                    byte[] bodyBytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bodyBytes);
                    DataBufferUtils.release(dataBuffer);
                    return bodyBytes;
                });
    }

    static ServerWebExchange decorate(ServerWebExchange exchange, byte[] bodyBytes) {
        DataBufferFactory bufferFactory = exchange.getResponse().bufferFactory();
        ServerHttpRequest decoratedRequest = new ServerHttpRequestDecorator(exchange.getRequest()) {
            @Override
            public HttpHeaders getHeaders() {
                HttpHeaders headers = new HttpHeaders();
                headers.putAll(super.getHeaders());
                headers.remove(HttpHeaders.CONTENT_LENGTH);
                headers.setContentLength(bodyBytes.length);
                return headers;
            }

            @Override
            public Flux<DataBuffer> getBody() {
                return Flux.defer(() -> Flux.just(bufferFactory.wrap(bodyBytes)));
            }
        };
        return exchange.mutate().request(decoratedRequest).build();
    }
}
