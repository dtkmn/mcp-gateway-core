package mcp.gateway.spring.webflux;

import java.util.Objects;
import mcp.gateway.core.invocation.McpToolInvocation;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Parses MCP JSON-RPC messages into normalized core invocation values.
 * <p>
 * The parser is intentionally scoped to the MCP request shape needed by gateway
 * governance. Its internal classification distinguishes request messages from
 * response envelopes and malformed or unsupported bodies. JSON-RPC responses do
 * not represent invocations, so the public parsing contract returns
 * {@link McpToolInvocation#unknown()} for them. It does not require or validate
 * the JSON-RPC {@code jsonrpc} version field for the current public-preview
 * contract.
 */
public final class McpJsonRpcToolInvocationParser {
    private final JsonMapper jsonMapper;

    /**
     * Creates a parser backed by Jackson.
     *
     * @param jsonMapper JSON mapper used to parse message bodies
     */
    public McpJsonRpcToolInvocationParser(JsonMapper jsonMapper) {
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper must not be null")
                .rebuild()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
    }

    /**
     * Parses a JSON-RPC message body into a core invocation.
     * <p>
     * Invalid message bodies and response envelopes are normalized to
     * {@link McpToolInvocation#unknown()}.
     * Adapters that need fail-closed behavior should use their own message-shape
     * classification path rather than treating {@code UNKNOWN} as a safe
     * pass-through signal.
     *
     * @param bodyBytes raw message body bytes
     * @return normalized invocation, or unknown when parsing fails
     */
    public McpToolInvocation parse(byte[] bodyBytes) {
        return classify(bodyBytes).invocation();
    }

    McpJsonRpcMessageClassification classify(byte[] bodyBytes) {
        if (emptyBody(bodyBytes)) {
            return McpJsonRpcMessageClassification.rejected(McpJsonRpcRequestRejectionReason.EMPTY_BODY);
        }

        JsonNode root;
        try (JsonParser jsonParser = jsonMapper.createParser(bodyBytes)) {
            root = jsonMapper.readTree(jsonParser);
            if (jsonParser.nextToken() != null) {
                return McpJsonRpcMessageClassification.rejected(McpJsonRpcRequestRejectionReason.MALFORMED_JSON);
            }
        } catch (JacksonException e) {
            return McpJsonRpcMessageClassification.rejected(McpJsonRpcRequestRejectionReason.MALFORMED_JSON);
        }

        if (root == null || root.isNull() || root.isValueNode()) {
            return McpJsonRpcMessageClassification.rejected(McpJsonRpcRequestRejectionReason.INVALID_REQUEST_SHAPE);
        }
        if (root.isArray()) {
            return McpJsonRpcMessageClassification.rejected(McpJsonRpcRequestRejectionReason.BATCH_NOT_SUPPORTED);
        }
        if (!root.isObject()) {
            return McpJsonRpcMessageClassification.rejected(McpJsonRpcRequestRejectionReason.INVALID_REQUEST_SHAPE);
        }

        if (hasCaseVariantField(root, "method")) {
            return McpJsonRpcMessageClassification.rejected(McpJsonRpcRequestRejectionReason.INVALID_METHOD);
        }
        JsonNode methodNode = root.get("method");
        if (methodNode == null) {
            return isResponseEnvelope(root)
                    ? McpJsonRpcMessageClassification.responseMessage()
                    : McpJsonRpcMessageClassification.rejected(McpJsonRpcRequestRejectionReason.MISSING_METHOD);
        }
        if (methodNode.isNull()) {
            return McpJsonRpcMessageClassification.rejected(McpJsonRpcRequestRejectionReason.MISSING_METHOD);
        }
        if (!methodNode.isString()
                || methodNode.stringValue().isBlank()
                || hasBoundaryWhitespace(methodNode.stringValue())) {
            return McpJsonRpcMessageClassification.rejected(McpJsonRpcRequestRejectionReason.INVALID_METHOD);
        }

        String method = methodNode.stringValue();
        if (!McpToolInvocation.METHOD_TOOLS_CALL.equals(method)) {
            return McpJsonRpcMessageClassification.request(McpToolInvocation.fromJsonRpc(method, null));
        }

        if (hasCaseVariantField(root, "params")) {
            return McpJsonRpcMessageClassification.rejected(McpJsonRpcRequestRejectionReason.INVALID_TOOL_CALL_PARAMS);
        }
        JsonNode params = root.get("params");
        if (params == null || params.isNull() || !params.isObject()) {
            return McpJsonRpcMessageClassification.rejected(McpJsonRpcRequestRejectionReason.INVALID_TOOL_CALL_PARAMS);
        }
        if (hasCaseVariantField(params, "name")) {
            return McpJsonRpcMessageClassification.rejected(McpJsonRpcRequestRejectionReason.INVALID_TOOL_NAME);
        }
        JsonNode toolNameNode = params.get("name");
        if (toolNameNode == null || toolNameNode.isNull()) {
            return McpJsonRpcMessageClassification.rejected(McpJsonRpcRequestRejectionReason.MISSING_TOOL_NAME);
        }
        if (!toolNameNode.isString()
                || toolNameNode.stringValue().isBlank()
                || hasBoundaryWhitespace(toolNameNode.stringValue())) {
            return McpJsonRpcMessageClassification.rejected(McpJsonRpcRequestRejectionReason.INVALID_TOOL_NAME);
        }
        return McpJsonRpcMessageClassification.request(McpToolInvocation.fromJsonRpc(method, toolNameNode.stringValue()));
    }

    private boolean isResponseEnvelope(JsonNode root) {
        JsonNode idNode = root.get("id");
        if (idNode == null || (!idNode.isString() && !idNode.isNumber())) {
            return false;
        }
        return root.has("result") ^ root.has("error");
    }

    private boolean hasCaseVariantField(JsonNode object, String expectedName) {
        for (String name : object.propertyNames()) {
            if (!expectedName.equals(name) && expectedName.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasBoundaryWhitespace(String value) {
        int first = value.codePointAt(0);
        int last = value.codePointBefore(value.length());
        return isWhitespace(first) || isWhitespace(last);
    }

    private boolean isWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
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
