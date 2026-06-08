package mcp.gateway.core.invocation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class McpToolInvocationTest {

    @Test
    void recognizesToolCall() {
        McpToolInvocation invocation = McpToolInvocation.fromJsonRpc(" tools/call ", " example_tool ");

        assertEquals(McpToolInvocationKind.TOOL_CALL, invocation.kind());
        assertEquals("tools/call", invocation.method());
        assertEquals("example_tool", invocation.toolName());
        assertEquals("example_tool", invocation.actionName());
        assertTrue(invocation.authorizable());
    }

    @Test
    void recognizesToolsListAsGatewayAction() {
        McpToolInvocation invocation = McpToolInvocation.fromJsonRpc("tools/list", null);

        assertEquals(McpToolInvocationKind.TOOLS_LIST, invocation.kind());
        assertEquals("tools/list", invocation.method());
        assertNull(invocation.toolName());
        assertEquals("tools/list", invocation.actionName());
        assertTrue(invocation.authorizable());
    }

    @Test
    void toolCallWithoutNameIsNotAuthorizable() {
        McpToolInvocation invocation = McpToolInvocation.fromJsonRpc("tools/call", " ");

        assertEquals(McpToolInvocationKind.UNKNOWN, invocation.kind());
        assertEquals("tools/call", invocation.method());
        assertNull(invocation.toolName());
        assertFalse(invocation.authorizable());
    }

    @Test
    void preservesOtherJsonRpcMethodsForProtectionDecisions() {
        McpToolInvocation invocation = McpToolInvocation.fromJsonRpc("ping", "ignored");

        assertEquals(McpToolInvocationKind.OTHER, invocation.kind());
        assertEquals("ping", invocation.method());
        assertNull(invocation.toolName());
        assertEquals("ping", invocation.actionName());
        assertFalse(invocation.authorizable());
    }

    @Test
    void emptyPayloadActionIsUnknown() {
        McpToolInvocation invocation = McpToolInvocation.unknown();

        assertEquals(McpToolInvocationKind.UNKNOWN, invocation.kind());
        assertNull(invocation.method());
        assertNull(invocation.toolName());
        assertNull(invocation.actionName());
        assertFalse(invocation.authorizable());
    }
}
