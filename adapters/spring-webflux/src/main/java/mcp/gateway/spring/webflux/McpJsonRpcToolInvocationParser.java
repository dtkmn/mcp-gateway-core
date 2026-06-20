package mcp.gateway.spring.webflux;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
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
        return classify(bodyBytes).invocation();
    }

    McpJsonRpcRequestClassification classify(byte[] bodyBytes) {
        if (emptyBody(bodyBytes)) {
            return McpJsonRpcRequestClassification.rejected(McpJsonRpcRequestRejectionReason.EMPTY_BODY);
        }

        JsonNode root;
        try (JsonParser jsonParser = objectMapper.getFactory().createParser(bodyBytes)) {
            root = objectMapper.readTree(jsonParser);
            if (jsonParser.nextToken() != null) {
                return McpJsonRpcRequestClassification.rejected(McpJsonRpcRequestRejectionReason.MALFORMED_JSON);
            }
        } catch (IOException e) {
            return McpJsonRpcRequestClassification.rejected(McpJsonRpcRequestRejectionReason.MALFORMED_JSON);
        }

        if (root == null || root.isNull() || root.isValueNode()) {
            return McpJsonRpcRequestClassification.rejected(McpJsonRpcRequestRejectionReason.INVALID_REQUEST_SHAPE);
        }
        if (root.isArray()) {
            return McpJsonRpcRequestClassification.rejected(McpJsonRpcRequestRejectionReason.BATCH_NOT_SUPPORTED);
        }
        if (!root.isObject()) {
            return McpJsonRpcRequestClassification.rejected(McpJsonRpcRequestRejectionReason.INVALID_REQUEST_SHAPE);
        }

        JsonNode methodNode = root.get("method");
        if (methodNode == null || methodNode.isNull()) {
            return McpJsonRpcRequestClassification.rejected(McpJsonRpcRequestRejectionReason.MISSING_METHOD);
        }
        if (!methodNode.isTextual() || methodNode.textValue().isBlank()) {
            return McpJsonRpcRequestClassification.rejected(McpJsonRpcRequestRejectionReason.INVALID_METHOD);
        }

        String method = methodNode.textValue().trim();
        if (!McpToolInvocation.METHOD_TOOLS_CALL.equals(method)) {
            return McpJsonRpcRequestClassification.valid(McpToolInvocation.fromJsonRpc(method, null));
        }

        JsonNode params = root.get("params");
        if (params == null || params.isNull() || !params.isObject()) {
            return McpJsonRpcRequestClassification.rejected(McpJsonRpcRequestRejectionReason.INVALID_TOOL_CALL_PARAMS);
        }
        JsonNode toolNameNode = params.get("name");
        if (toolNameNode == null || toolNameNode.isNull()) {
            return McpJsonRpcRequestClassification.rejected(McpJsonRpcRequestRejectionReason.MISSING_TOOL_NAME);
        }
        if (!toolNameNode.isTextual() || toolNameNode.textValue().isBlank()) {
            return McpJsonRpcRequestClassification.rejected(McpJsonRpcRequestRejectionReason.INVALID_TOOL_NAME);
        }
        return McpJsonRpcRequestClassification.valid(McpToolInvocation.fromJsonRpc(method, toolNameNode.textValue()));
    }

    private boolean emptyBody(byte[] bodyBytes) {
        if (bodyBytes == null || bodyBytes.length == 0) {
            return true;
        }
        for (byte value : bodyBytes) {
            if (value != 0x20 && value != 0x09 && value != 0x0a && value != 0x0d) {
                return false;
            }
        }
        return true;
    }
}
