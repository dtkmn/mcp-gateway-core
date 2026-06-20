package mcp.gateway.spring.webflux;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import mcp.gateway.core.invocation.McpToolInvocation;
import mcp.gateway.core.invocation.McpToolInvocationKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class McpJsonRpcToolInvocationParserTest {
    private final McpJsonRpcToolInvocationParser parser = new McpJsonRpcToolInvocationParser(new ObjectMapper());

    @Test
    void parsesToolCall() {
        McpToolInvocation invocation = parser.parse(bytes("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"demo_tool"}}
                """));

        assertEquals(McpToolInvocationKind.TOOL_CALL, invocation.kind());
        assertEquals("tools/call", invocation.method());
        assertEquals("demo_tool", invocation.toolName());
        assertEquals("demo_tool", invocation.actionName());
    }

    @Test
    void parsesToolsList() {
        McpToolInvocation invocation = parser.parse(bytes("""
                {"method":"tools/list","params":{}}
                """));

        assertEquals(McpToolInvocationKind.TOOLS_LIST, invocation.kind());
        assertEquals("tools/list", invocation.actionName());
        assertNull(invocation.toolName());
    }

    @Test
    void parsesValidNonToolMethodAsOther() {
        McpToolInvocation invocation = parser.parse(bytes("""
                {"jsonrpc":"2.0","id":1,"method":"ping"}
                """));

        assertEquals(McpToolInvocationKind.OTHER, invocation.kind());
        assertEquals("ping", invocation.actionName());
        assertNull(invocation.toolName());
    }

    @Test
    void doesNotRequireOrValidateJsonRpcVersionForGovernanceShape() {
        assertEquals(McpToolInvocationKind.TOOLS_LIST, parser.parse(bytes("""
                {"method":"tools/list"}
                """)).kind());
        assertEquals(McpToolInvocationKind.TOOLS_LIST, parser.parse(bytes("""
                {"jsonrpc":null,"method":"tools/list"}
                """)).kind());
        assertEquals(McpToolInvocationKind.TOOLS_LIST, parser.parse(bytes("""
                {"jsonrpc":2,"method":"tools/list"}
                """)).kind());
        assertEquals(McpToolInvocationKind.TOOLS_LIST, parser.parse(bytes("""
                {"jsonrpc":"1.0","method":"tools/list"}
                """)).kind());
    }

    @Test
    void classifiesInvalidRequestShapesWithStableReasons() {
        assertReason(null, McpJsonRpcRequestRejectionReason.EMPTY_BODY);
        assertReason("", McpJsonRpcRequestRejectionReason.EMPTY_BODY);
        assertReason("  \r\n\t", McpJsonRpcRequestRejectionReason.EMPTY_BODY);
        assertReason("not-json", McpJsonRpcRequestRejectionReason.MALFORMED_JSON);
        assertReason("{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\"} garbage",
                McpJsonRpcRequestRejectionReason.MALFORMED_JSON);
        assertReason("[]", McpJsonRpcRequestRejectionReason.BATCH_NOT_SUPPORTED);
        assertReason("[{}]", McpJsonRpcRequestRejectionReason.BATCH_NOT_SUPPORTED);
        assertReason("null", McpJsonRpcRequestRejectionReason.INVALID_REQUEST_SHAPE);
        assertReason("42", McpJsonRpcRequestRejectionReason.INVALID_REQUEST_SHAPE);
        assertReason("\"tools/list\"", McpJsonRpcRequestRejectionReason.INVALID_REQUEST_SHAPE);
        assertReason("{\"jsonrpc\":\"2.0\"}", McpJsonRpcRequestRejectionReason.MISSING_METHOD);
        assertReason("{\"jsonrpc\":\"2.0\",\"method\":null}", McpJsonRpcRequestRejectionReason.MISSING_METHOD);
        assertReason("{\"jsonrpc\":\"2.0\",\"method\":7}", McpJsonRpcRequestRejectionReason.INVALID_METHOD);
        assertReason("{\"jsonrpc\":\"2.0\",\"method\":true}", McpJsonRpcRequestRejectionReason.INVALID_METHOD);
        assertReason("{\"jsonrpc\":\"2.0\",\"method\":\"  \"}", McpJsonRpcRequestRejectionReason.INVALID_METHOD);
        assertReason("{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\"}",
                McpJsonRpcRequestRejectionReason.INVALID_TOOL_CALL_PARAMS);
        assertReason("{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"params\":null}",
                McpJsonRpcRequestRejectionReason.INVALID_TOOL_CALL_PARAMS);
        assertReason("{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"params\":[]}",
                McpJsonRpcRequestRejectionReason.INVALID_TOOL_CALL_PARAMS);
        assertReason("{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"params\":{}}",
                McpJsonRpcRequestRejectionReason.MISSING_TOOL_NAME);
        assertReason("{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"params\":{\"name\":null}}",
                McpJsonRpcRequestRejectionReason.MISSING_TOOL_NAME);
        assertReason("{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"params\":{\"name\":7}}",
                McpJsonRpcRequestRejectionReason.INVALID_TOOL_NAME);
        assertReason("{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"params\":{\"name\":false}}",
                McpJsonRpcRequestRejectionReason.INVALID_TOOL_NAME);
        assertReason("{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"params\":{\"name\":\"  \"}}",
                McpJsonRpcRequestRejectionReason.INVALID_TOOL_NAME);
    }

    @Test
    void parseReturnsUnknownForRejectedShapes() {
        assertEquals(McpToolInvocationKind.UNKNOWN, parser.parse(bytes("not-json")).kind());
        assertEquals(McpToolInvocationKind.UNKNOWN, parser.parse(bytes("[{}]")).kind());
        assertEquals(McpToolInvocationKind.UNKNOWN, parser.parse(new byte[0]).kind());
    }

    private void assertReason(String json, McpJsonRpcRequestRejectionReason expected) {
        McpJsonRpcRequestClassification classification = parser.classify(json == null ? null : bytes(json));

        assertEquals(expected, classification.rejectionReason());
        assertEquals(McpToolInvocationKind.UNKNOWN, classification.invocation().kind());
    }

    private byte[] bytes(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }
}
