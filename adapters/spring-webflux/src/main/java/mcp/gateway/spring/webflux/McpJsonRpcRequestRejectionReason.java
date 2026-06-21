package mcp.gateway.spring.webflux;

enum McpJsonRpcRequestRejectionReason {
    EMPTY_BODY("invalid_request_shape"),
    MALFORMED_JSON("invalid_json_rpc_request"),
    BATCH_NOT_SUPPORTED("batch_not_supported"),
    INVALID_REQUEST_SHAPE("invalid_request_shape"),
    MISSING_METHOD("invalid_request_shape"),
    INVALID_METHOD("invalid_request_shape"),
    INVALID_TOOL_CALL_PARAMS("invalid_request_shape"),
    MISSING_TOOL_NAME("invalid_request_shape"),
    INVALID_TOOL_NAME("invalid_request_shape");

    private final String code;

    McpJsonRpcRequestRejectionReason(String code) {
        this.code = code;
    }

    String code() {
        return code;
    }
}
