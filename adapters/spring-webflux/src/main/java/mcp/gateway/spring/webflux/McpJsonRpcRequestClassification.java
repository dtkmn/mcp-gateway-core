package mcp.gateway.spring.webflux;

import mcp.gateway.core.invocation.McpToolInvocation;

record McpJsonRpcRequestClassification(McpToolInvocation invocation,
                                        McpJsonRpcRequestRejectionReason rejectionReason) {
    static McpJsonRpcRequestClassification valid(McpToolInvocation invocation) {
        return new McpJsonRpcRequestClassification(invocation, null);
    }

    static McpJsonRpcRequestClassification rejected(McpJsonRpcRequestRejectionReason reason) {
        return new McpJsonRpcRequestClassification(McpToolInvocation.unknown(), reason);
    }

    boolean valid() {
        return rejectionReason == null;
    }
}
