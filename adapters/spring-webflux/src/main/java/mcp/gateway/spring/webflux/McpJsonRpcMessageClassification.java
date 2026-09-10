package mcp.gateway.spring.webflux;

import mcp.gateway.core.invocation.McpToolInvocation;
import tools.jackson.databind.JsonNode;

record McpJsonRpcMessageClassification(McpToolInvocation invocation,
                                       McpJsonRpcRequestRejectionReason rejectionReason,
                                       boolean response,
                                       JsonNode requestId,
                                       boolean invalidRequestId) {
    static McpJsonRpcMessageClassification request(McpToolInvocation invocation,
                                                    JsonNode requestId,
                                                    boolean invalidRequestId) {
        return new McpJsonRpcMessageClassification(invocation, null, false, requestId, invalidRequestId);
    }

    static McpJsonRpcMessageClassification responseMessage(JsonNode requestId, boolean invalidRequestId) {
        return new McpJsonRpcMessageClassification(
                McpToolInvocation.unknown(), null, true, requestId, invalidRequestId);
    }

    static McpJsonRpcMessageClassification rejected(McpJsonRpcRequestRejectionReason reason) {
        return new McpJsonRpcMessageClassification(McpToolInvocation.unknown(), reason, false, null, false);
    }

    boolean valid() {
        return rejectionReason == null;
    }

    boolean notification() {
        return valid() && !response && !invalidRequestId && requestId == null;
    }
}
