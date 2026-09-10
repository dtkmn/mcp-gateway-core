package mcp.gateway.spring.webflux;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import mcp.gateway.core.authz.ToolAuthorizationDecision;
import mcp.gateway.core.protection.McpAbuseProtectionDecision;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

final class McpGatewayWebFluxResponses {
    private McpGatewayWebFluxResponses() {
    }

    static Mono<Void> unknownTool(ServerWebExchange exchange, JsonMapper jsonMapper, JsonNode requestId) {
        return jsonRpcError(exchange, jsonMapper, HttpStatus.OK, requestId, -32602, "Unknown tool");
    }

    static Mono<Void> internalToolError(ServerWebExchange exchange, JsonMapper jsonMapper, JsonNode requestId) {
        return jsonRpcError(exchange, jsonMapper, HttpStatus.OK, requestId, -32603, "Internal error");
    }

    static Mono<Void> invalidToolCallId(ServerWebExchange exchange, JsonMapper jsonMapper) {
        return jsonRpcError(exchange, jsonMapper, HttpStatus.BAD_REQUEST, null, -32600, "Invalid Request");
    }

    static Mono<Void> notificationAccepted(ServerWebExchange exchange) {
        return Mono.defer(() -> {
            exchange.getResponse().setStatusCode(HttpStatus.ACCEPTED);
            exchange.getResponse().getHeaders().remove(HttpHeaders.WWW_AUTHENTICATE);
            return exchange.getResponse().setComplete();
        });
    }

    private static Mono<Void> jsonRpcError(ServerWebExchange exchange,
                                           JsonMapper jsonMapper,
                                           HttpStatus status,
                                           JsonNode requestId,
                                           int code,
                                           String message) {
        return Mono.defer(() -> {
            byte[] bytes;
            try {
                ObjectNode body = jsonMapper.createObjectNode();
                body.put("jsonrpc", "2.0");
                body.set("id", requestId);
                ObjectNode error = body.putObject("error");
                error.put("code", code);
                error.put("message", message);
                bytes = jsonMapper.writeValueAsBytes(body);
            } catch (Exception exception) {
                // A generic fallback could lose or corrupt the request ID. Leave the
                // response uncommitted and propagate a non-sensitive failure instead.
                return Mono.error(new IllegalStateException("Unable to serialize MCP error response"));
            }
            exchange.getResponse().setStatusCode(status);
            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
            exchange.getResponse().getHeaders().remove(HttpHeaders.WWW_AUTHENTICATE);
            return exchange.getResponse().writeWith(
                    Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
        });
    }

    static Mono<Void> forbidden(ServerWebExchange exchange,
                                JsonMapper jsonMapper,
                                ToolAuthorizationDecision decision,
                                String errorCode,
                                String correlationId) {
        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().set(
                HttpHeaders.WWW_AUTHENTICATE,
                insufficientScopeChallenge(decision)
        );

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", HttpStatus.FORBIDDEN.value());
        body.put("error", errorCode);
        body.put("tool", decision.actionName());
        body.put("requiredScopes", decision.requiredScopes());
        body.put("grantedScopes", decision.grantedScopes());
        body.put("correlationId", correlationId);
        body.put("requestId", exchange.getRequest().getId());
        return writeJson(exchange, jsonMapper, body, "{\"error\":\"insufficient_scope\"}");
    }

    static Mono<Void> protectionRejected(ServerWebExchange exchange,
                                         JsonMapper jsonMapper,
                                         McpAbuseProtectionDecision decision,
                                         String correlationId) {
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().set(
                HttpHeaders.RETRY_AFTER,
                String.valueOf(Math.max(1L, decision.retryAfterSeconds()))
        );

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", HttpStatus.TOO_MANY_REQUESTS.value());
        body.put("error", decision.errorCode());
        body.put("reason", decision.reason());
        body.put("tool", decision.toolName());
        body.put("clientId", decision.clientId());
        body.put("workspaceId", decision.workspaceId());
        body.put("retryAfterSeconds", Math.max(1L, decision.retryAfterSeconds()));
        body.put("correlationId", correlationId);
        body.put("requestId", exchange.getRequest().getId());
        return writeJson(exchange, jsonMapper, body, "{\"error\":\"rate_limited\"}");
    }

    static Mono<Void> payloadTooLarge(ServerWebExchange exchange,
                                      JsonMapper jsonMapper,
                                      int maxBodyBytes,
                                      String correlationId) {
        exchange.getResponse().setStatusCode(HttpStatus.CONTENT_TOO_LARGE);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", HttpStatus.CONTENT_TOO_LARGE.value());
        body.put("error", "request_body_too_large");
        body.put("reason", "MCP request body exceeds the configured limit");
        body.put("maxBodyBytes", maxBodyBytes);
        body.put("correlationId", correlationId);
        body.put("requestId", exchange.getRequest().getId());
        return writeJson(exchange, jsonMapper, body, "{\"error\":\"request_body_too_large\"}");
    }

    static Mono<Void> invalidRequest(ServerWebExchange exchange,
                                     JsonMapper jsonMapper,
                                     String reason,
                                     String correlationId) {
        exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", HttpStatus.BAD_REQUEST.value());
        body.put("error", "invalid_json_rpc_request");
        body.put("reason", reason);
        body.put("correlationId", correlationId);
        body.put("requestId", exchange.getRequest().getId());
        return writeJson(exchange, jsonMapper, body, "{\"error\":\"invalid_json_rpc_request\"}");
    }

    private static Mono<Void> writeJson(ServerWebExchange exchange,
                                        JsonMapper jsonMapper,
                                        Map<String, Object> body,
                                        String fallbackJson) {
        try {
            byte[] bytes = jsonMapper.writeValueAsBytes(body);
            return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
        } catch (Exception e) {
            return exchange.getResponse().writeWith(Mono.just(exchange.getResponse()
                    .bufferFactory()
                    .wrap(fallbackJson.getBytes(StandardCharsets.UTF_8))));
        }
    }

    private static String insufficientScopeChallenge(ToolAuthorizationDecision decision) {
        String challenge = "Bearer error=\"insufficient_scope\"";
        if (decision.requiredScopes().isEmpty()
                || !decision.requiredScopes().stream().allMatch(McpGatewayWebFluxResponses::isScopeToken)) {
            return challenge;
        }
        return challenge + ", scope=\"" + String.join(" ", decision.requiredScopes()) + "\"";
    }

    private static boolean isScopeToken(String scope) {
        if (scope == null || scope.isEmpty()) {
            return false;
        }
        for (int index = 0; index < scope.length(); index++) {
            char value = scope.charAt(index);
            // RFC 6749 scope-token: %x21 / %x23-5B / %x5D-7E.
            if (value != 0x21 && (value < 0x23 || value > 0x5b) && (value < 0x5d || value > 0x7e)) {
                return false;
            }
        }
        return true;
    }
}
