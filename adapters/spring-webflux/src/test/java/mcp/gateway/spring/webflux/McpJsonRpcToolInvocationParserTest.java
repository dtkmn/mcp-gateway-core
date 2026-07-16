package mcp.gateway.spring.webflux;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import mcp.gateway.core.invocation.McpToolInvocation;
import mcp.gateway.core.invocation.McpToolInvocationKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void recognizesResponseEnvelopesWithoutTurningThemIntoInvocations() {
        for (String body : new String[]{
                "{\"jsonrpc\":\"2.0\",\"id\":\"server-ping-1\",\"result\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":null}",
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"error\":{\"code\":-32603,\"message\":\"failed\"}}",
                "{\"id\":4,\"result\":{}}",
                "{\"jsonrpc\":\"1.0\",\"id\":5,\"result\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":6,\"result\":\"downstream-validates\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"error\":null}"
        }) {
            McpJsonRpcMessageClassification classification = parser.classify(bytes(body));

            assertTrue(classification.valid(), body);
            assertTrue(classification.response(), body);
            assertNull(classification.rejectionReason(), body);
            assertEquals(McpToolInvocationKind.UNKNOWN, classification.invocation().kind(), body);
            assertEquals(McpToolInvocationKind.UNKNOWN, parser.parse(bytes(body)).kind(), body);
        }
    }

    @Test
    void rejectsMethodlessObjectsThatAreNotResponseEnvelopes() {
        assertReason("{\"jsonrpc\":\"2.0\",\"id\":1}",
                McpJsonRpcRequestRejectionReason.MISSING_METHOD);
        assertReason("{\"jsonrpc\":\"2.0\",\"result\":{}}",
                McpJsonRpcRequestRejectionReason.MISSING_METHOD);
        assertReason("{\"jsonrpc\":\"2.0\",\"id\":null,\"result\":{}}",
                McpJsonRpcRequestRejectionReason.MISSING_METHOD);
        assertReason("{\"jsonrpc\":\"2.0\",\"id\":true,\"result\":{}}",
                McpJsonRpcRequestRejectionReason.MISSING_METHOD);
        assertReason("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{},\"error\":{}}",
                McpJsonRpcRequestRejectionReason.MISSING_METHOD);
        assertReason("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":null,\"result\":{}}",
                McpJsonRpcRequestRejectionReason.MISSING_METHOD);
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
        assertReason("{\"jsonrpc\":\"2.0\",\"method\":\" tools/call\",\"params\":{\"name\":\"demo_tool\"}}",
                McpJsonRpcRequestRejectionReason.INVALID_METHOD);
        assertReason("{\"jsonrpc\":\"2.0\",\"method\":\"ping \"}",
                McpJsonRpcRequestRejectionReason.INVALID_METHOD);
        assertReason("{\"jsonrpc\":\"2.0\",\"method\":\" tools/call\",\"params\":{\"name\":\"demo_tool\"}}",
                McpJsonRpcRequestRejectionReason.INVALID_METHOD);
        assertReason("{\"jsonrpc\":\"2.0\",\"method\":\"ping\u00a0\"}",
                McpJsonRpcRequestRejectionReason.INVALID_METHOD);
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
        assertReason("{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"params\":{\"name\":\" demo_tool \"}}",
                McpJsonRpcRequestRejectionReason.INVALID_TOOL_NAME);
        assertReason("{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"params\":{\"name\":\" demo_tool\"}}",
                McpJsonRpcRequestRejectionReason.INVALID_TOOL_NAME);
        assertReason("{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"params\":{\"name\":\"demo_tool\u00a0\"}}",
                McpJsonRpcRequestRejectionReason.INVALID_TOOL_NAME);
    }

    @Test
    void rejectsDuplicateObjectFieldsToPreventParserDifferentials() {
        assertReason("{\"method\":\"tools/call\",\"method\":\"ping\",\"params\":{\"name\":\"demo_tool\"}}",
                McpJsonRpcRequestRejectionReason.MALFORMED_JSON);
        assertReason("{\"method\":\"tools/call\",\"params\":{\"name\":\"demo_tool\",\"name\":\"other_tool\"}}",
                McpJsonRpcRequestRejectionReason.MALFORMED_JSON);
        assertReason("{\"method\":\"tools/call\",\"params\":{\"name\":\"demo_tool\",\"arguments\":{\"value\":1,\"value\":2}}}",
                McpJsonRpcRequestRejectionReason.MALFORMED_JSON);
        assertReason("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{},\"result\":null}",
                McpJsonRpcRequestRejectionReason.MALFORMED_JSON);
    }

    @Test
    void rejectsCaseVariantGovernanceFieldsToPreventParserDifferentials() {
        assertReason("{\"Method\":\"tools/call\",\"id\":1,\"result\":{}}",
                McpJsonRpcRequestRejectionReason.INVALID_METHOD);
        assertReason("{\"method\":\"ping\",\"METHOD\":\"tools/call\",\"params\":{\"name\":\"restricted\"}}",
                McpJsonRpcRequestRejectionReason.INVALID_METHOD);
        assertReason("{\"method\":\"tools/call\",\"params\":{\"name\":\"allowed\"},\"Params\":{\"name\":\"restricted\"}}",
                McpJsonRpcRequestRejectionReason.INVALID_TOOL_CALL_PARAMS);
        assertReason("{\"method\":\"tools/call\",\"params\":{\"name\":\"allowed\",\"Name\":\"restricted\"}}",
                McpJsonRpcRequestRejectionReason.INVALID_TOOL_NAME);
    }

    @Test
    void parseReturnsUnknownForRejectedShapes() {
        assertEquals(McpToolInvocationKind.UNKNOWN, parser.parse(bytes("not-json")).kind());
        assertEquals(McpToolInvocationKind.UNKNOWN, parser.parse(bytes("[{}]")).kind());
        assertEquals(McpToolInvocationKind.UNKNOWN, parser.parse(new byte[0]).kind());
    }

    private void assertReason(String json, McpJsonRpcRequestRejectionReason expected) {
        McpJsonRpcMessageClassification classification = parser.classify(json == null ? null : bytes(json));

        assertEquals(expected, classification.rejectionReason());
        assertFalse(classification.response());
        assertEquals(McpToolInvocationKind.UNKNOWN, classification.invocation().kind());
    }

    private byte[] bytes(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }
}
