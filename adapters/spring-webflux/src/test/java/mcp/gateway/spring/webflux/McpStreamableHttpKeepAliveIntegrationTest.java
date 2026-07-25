package mcp.gateway.spring.webflux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpClientSession;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ProtocolVersions;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import mcp.gateway.core.context.GatewayToolExecutionContext;
import mcp.gateway.core.protection.McpAbuseProtectionDecision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.mcp.server.webflux.transport.WebFluxStreamableServerTransportProvider;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.FluxExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebFilter;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Timeout(15)
class McpStreamableHttpKeepAliveIntegrationTest {
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();
    private static final JacksonMcpJsonMapper MCP_JSON_MAPPER =
            new JacksonMcpJsonMapper(JSON_MAPPER);
    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_TYPE =
            new ParameterizedTypeReference<>() { };
    private static final String MCP_ENDPOINT = "/mcp";
    private static final String MCP_SESSION_ID = "Mcp-Session-Id";
    private static final String MCP_PROTOCOL_VERSION = "MCP-Protocol-Version";
    private static final String AUTHORIZATION = "Bearer integration-token";

    @Test
    void acceptsTheClientResponseToAServerInitiatedKeepAlivePingOnTheSameSession() throws Exception {
        CopyOnWriteArrayList<String> protectedActions = new CopyOnWriteArrayList<>();
        AtomicInteger authenticatedRequests = new AtomicInteger();
        AtomicReference<McpSchema.JSONRPCRequest> serverPing = new AtomicReference<>();
        AtomicReference<Throwable> listeningFailure = new AtomicReference<>();
        CountDownLatch serverPingLatch = new CountDownLatch(1);
        McpGatewayWebFluxGovernanceFilter governanceFilter = governanceFilter(protectedActions);
        WebFluxStreamableServerTransportProvider transportProvider =
                WebFluxStreamableServerTransportProvider.builder()
                        .jsonMapper(MCP_JSON_MAPPER)
                        .messageEndpoint(MCP_ENDPOINT)
                        // Compress the production interval without changing the Spring AI
                        // scheduler/session path under test.
                        .keepAliveInterval(Duration.ofSeconds(1))
                        .build();
        McpSyncServer mcpServer = McpServer.sync(transportProvider)
                .serverInfo("keep-alive-regression-server", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().build())
                .jsonMapper(MCP_JSON_MAPPER)
                .requestTimeout(Duration.ofSeconds(2))
                .build();
        WebFilter authenticationFilter = authenticationFilter(authenticatedRequests);
        WebTestClient http = WebTestClient.bindToRouterFunction(transportProvider.getRouterFunction())
                .webFilter(authenticationFilter, governanceFilter)
                .configureClient()
                .responseTimeout(Duration.ofSeconds(5))
                .defaultHeader(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                .build();
        WebTestClient unauthenticatedHttp = WebTestClient.bindToRouterFunction(transportProvider.getRouterFunction())
                .webFilter(authenticationFilter, governanceFilter)
                .configureClient()
                .responseTimeout(Duration.ofSeconds(5))
                .build();
        CapturingClientTransport clientTransport = new CapturingClientTransport();
        McpClientSession clientSession = new McpClientSession(
                Duration.ofSeconds(2),
                clientTransport,
                Map.<String, McpClientSession.RequestHandler<?>>of(
                        McpSchema.METHOD_PING,
                        ignored -> Mono.just(Map.of())
                ),
                Map.of(),
                Function.identity()
        );
        Disposable listeningSubscription = null;

        try {
            String sessionId = initialize(http);
            sendInitializedNotification(http, sessionId);
            FluxExchangeResult<ServerSentEvent<String>> listeningStream = openListeningStream(http, sessionId);
            listeningSubscription = listeningStream.getResponseBody().subscribe(event -> {
                try {
                    McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(
                            MCP_JSON_MAPPER,
                            event.data()
                    );
                    if (message instanceof McpSchema.JSONRPCRequest request
                            && McpSchema.METHOD_PING.equals(request.method())
                            && serverPing.compareAndSet(null, request)) {
                        clientTransport.receive(request);
                        serverPingLatch.countDown();
                    }
                }
                catch (Exception failure) {
                    listeningFailure.compareAndSet(null, failure);
                }
            }, failure -> listeningFailure.compareAndSet(null, failure));

            assertTrue(serverPingLatch.await(5, TimeUnit.SECONDS),
                    "server did not emit the keep-alive ping over the GET SSE stream");
            assertNull(listeningFailure.get());
            McpSchema.JSONRPCResponse response = clientTransport.awaitResponse();
            assertKeepAliveSemantics(serverPing.get(), response);

            unauthenticatedHttp.post()
                    .uri(MCP_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                    .header(MCP_SESSION_ID, sessionId)
                    .header(MCP_PROTOCOL_VERSION, ProtocolVersions.MCP_2025_11_25)
                    .bodyValue(MCP_JSON_MAPPER.writeValueAsString(response))
                    .exchange()
                    .expectStatus().isUnauthorized();

            http.post()
                    .uri(MCP_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                    .header(MCP_SESSION_ID, sessionId)
                    .header(MCP_PROTOCOL_VERSION, ProtocolVersions.MCP_2025_11_25)
                    .bodyValue(MCP_JSON_MAPPER.writeValueAsString(response))
                    .exchange()
                    .expectStatus().isAccepted();

            assertEquals(List.of("initialize", "notifications/initialized"), protectedActions);
            assertEquals(4, authenticatedRequests.get());
        }
        finally {
            if (listeningSubscription != null) {
                listeningSubscription.dispose();
            }
            clientSession.closeGracefully().block(Duration.ofSeconds(2));
            mcpServer.closeGracefully();
        }
    }

    private static String initialize(WebTestClient http) throws Exception {
        McpSchema.InitializeRequest params = McpSchema.InitializeRequest.builder(
                ProtocolVersions.MCP_2025_11_25,
                McpSchema.ClientCapabilities.builder().build(),
                McpSchema.Implementation.builder("keep-alive-regression-client", "1.0.0").build()
        ).build();
        McpSchema.JSONRPCRequest request = new McpSchema.JSONRPCRequest(
                McpSchema.JSONRPC_VERSION,
                McpSchema.METHOD_INITIALIZE,
                "initialize-1",
                params
        );
        EntityExchangeResult<byte[]> result = http.post()
                .uri(MCP_ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .header(MCP_PROTOCOL_VERSION, ProtocolVersions.MCP_2025_11_25)
                .bodyValue(MCP_JSON_MAPPER.writeValueAsString(request))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists(MCP_SESSION_ID)
                .expectBody()
                .returnResult();
        String sessionId = result.getResponseHeaders().getFirst(MCP_SESSION_ID);
        assertNotNull(sessionId);
        assertFalse(sessionId.isBlank());
        return sessionId;
    }

    private static void sendInitializedNotification(WebTestClient http, String sessionId) throws Exception {
        McpSchema.JSONRPCNotification notification = new McpSchema.JSONRPCNotification(
                McpSchema.JSONRPC_VERSION,
                McpSchema.METHOD_NOTIFICATION_INITIALIZED,
                null
        );
        http.post()
                .uri(MCP_ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .header(MCP_SESSION_ID, sessionId)
                .header(MCP_PROTOCOL_VERSION, ProtocolVersions.MCP_2025_11_25)
                .bodyValue(MCP_JSON_MAPPER.writeValueAsString(notification))
                .exchange()
                .expectStatus().isAccepted();
    }

    private static FluxExchangeResult<ServerSentEvent<String>> openListeningStream(WebTestClient http,
                                                                                    String sessionId) {
        return http.get()
                .uri(MCP_ENDPOINT)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .header(MCP_SESSION_ID, sessionId)
                .header(MCP_PROTOCOL_VERSION, ProtocolVersions.MCP_2025_11_25)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(SSE_TYPE);
    }

    private static void assertKeepAliveSemantics(McpSchema.JSONRPCRequest ping,
                                                 McpSchema.JSONRPCResponse response) throws Exception {
        assertNotNull(ping);
        assertEquals(McpSchema.JSONRPC_VERSION, ping.jsonrpc());
        assertEquals(McpSchema.METHOD_PING, ping.method());
        assertInstanceOf(String.class, ping.id());
        assertFalse(((String) ping.id()).isBlank());

        assertEquals(McpSchema.JSONRPC_VERSION, response.jsonrpc());
        assertEquals(ping.id(), response.id());
        assertEquals(Map.of(), response.result());
        assertNull(response.error());

        JsonNode serialized = JSON_MAPPER.readTree(MCP_JSON_MAPPER.writeValueAsString(response));
        assertFalse(serialized.has("method"));
        assertEquals(ping.id(), serialized.path("id").stringValue());
        assertTrue(serialized.path("result").isObject());
        assertEquals(0, serialized.path("result").size());
        assertFalse(serialized.has("error"));
    }

    private static McpGatewayWebFluxGovernanceFilter governanceFilter(List<String> protectedActions) {
        McpGatewayAbuseProtectionEvaluator protectionEvaluator = new McpGatewayAbuseProtectionEvaluator() {
            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public McpAbuseProtectionDecision evaluate(GatewayToolExecutionContext context) {
                protectedActions.add(context.actionName());
                return McpAbuseProtectionDecision.allow(
                        context.actionName(),
                        context.principalId(),
                        context.workspaceId()
                );
            }
        };
        return new McpGatewayWebFluxGovernanceFilter(
                JSON_MAPPER,
                McpGatewayWebFluxProperties.defaults(),
                null,
                protectionEvaluator,
                (authentication, exchange, invocation) -> GatewayToolExecutionContext.of(
                        authentication == null ? null : authentication.getName(),
                        "integration-workspace",
                        exchange.getRequest().getId(),
                        invocation,
                        null
                )
        );
    }

    private static WebFilter authenticationFilter(AtomicInteger authenticatedRequests) {
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                "integration-client",
                "not-used",
                List.of()
        );
        return (exchange, chain) -> {
            if (!AUTHORIZATION.equals(exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION))) {
                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                return exchange.getResponse().setComplete();
            }
            authenticatedRequests.incrementAndGet();
            return chain.filter(exchange.mutate().principal(Mono.just(authentication)).build());
        };
    }

    private static final class CapturingClientTransport implements McpClientTransport {
        private final AtomicReference<Function<Mono<McpSchema.JSONRPCMessage>,
                Mono<McpSchema.JSONRPCMessage>>> inboundHandler = new AtomicReference<>();
        private final Sinks.One<McpSchema.JSONRPCResponse> response = Sinks.one();

        @Override
        public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>,
                Mono<McpSchema.JSONRPCMessage>> handler) {
            inboundHandler.set(handler);
            return Mono.empty();
        }

        @Override
        public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
            if (message instanceof McpSchema.JSONRPCResponse jsonRpcResponse) {
                response.tryEmitValue(jsonRpcResponse);
            }
            return Mono.empty();
        }

        @Override
        public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
            return MCP_JSON_MAPPER.convertValue(data, typeRef);
        }

        @Override
        public Mono<Void> closeGracefully() {
            return Mono.empty();
        }

        void receive(McpSchema.JSONRPCMessage message) {
            Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler =
                    inboundHandler.get();
            if (handler == null) {
                response.tryEmitError(new IllegalStateException("client session is not connected"));
                return;
            }
            handler.apply(Mono.just(message)).subscribe(
                    ignored -> { },
                    response::tryEmitError
            );
        }

        McpSchema.JSONRPCResponse awaitResponse() {
            McpSchema.JSONRPCResponse captured = response.asMono().block(Duration.ofSeconds(2));
            assertNotNull(captured);
            return captured;
        }
    }
}
