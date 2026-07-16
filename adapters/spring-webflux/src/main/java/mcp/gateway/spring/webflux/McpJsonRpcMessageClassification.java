package mcp.gateway.spring.webflux;

import mcp.gateway.core.invocation.McpToolInvocation;

record McpJsonRpcMessageClassification(McpToolInvocation invocation,
                                       McpJsonRpcRequestRejectionReason rejectionReason,
                                       boolean response) {
    static McpJsonRpcMessageClassification request(McpToolInvocation invocation) {
        return new McpJsonRpcMessageClassification(invocation, null, false);
    }

    static McpJsonRpcMessageClassification responseMessage() {
        return new McpJsonRpcMessageClassification(McpToolInvocation.unknown(), null, true);
    }

    static McpJsonRpcMessageClassification rejected(McpJsonRpcRequestRejectionReason reason) {
        return new McpJsonRpcMessageClassification(McpToolInvocation.unknown(), reason, false);
    }

    boolean valid() {
        return rejectionReason == null;
    }
}
