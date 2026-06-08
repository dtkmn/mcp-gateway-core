package mcp.gateway.spring.webflux;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import mcp.gateway.core.invocation.McpToolInvocation;

/**
 * Parses MCP JSON-RPC request bodies into normalized core invocation values.
 */
public final class McpJsonRpcToolInvocationParser {
    private final ObjectMapper objectMapper;

    /**
     * Creates a parser backed by Jackson.
     *
     * @param objectMapper object mapper used to parse request bodies
     */
    public McpJsonRpcToolInvocationParser(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /**
     * Parses a request body into a core invocation.
     *
     * @param bodyBytes raw request body bytes
     * @return normalized invocation, or unknown when parsing fails
     */
    public McpToolInvocation parse(byte[] bodyBytes) {
        if (bodyBytes == null || bodyBytes.length == 0) {
            return McpToolInvocation.unknown();
        }

        try {
            JsonNode root = objectMapper.readTree(bodyBytes);
            if (root == null || !root.isObject()) {
                return McpToolInvocation.unknown();
            }

            String method = textValue(root.get("method"));
            JsonNode params = root.get("params");
            String toolName = params != null ? textValue(params.get("name")) : null;
            return McpToolInvocation.fromJsonRpc(method, toolName);
        } catch (Exception e) {
            return McpToolInvocation.unknown();
        }
    }

    private String textValue(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText(null);
    }
}
