package mcp.gateway.spring.webflux;

import com.fasterxml.jackson.databind.ObjectMapper;
import mcp.gateway.core.invocation.McpToolInvocation;
import mcp.gateway.core.invocation.McpToolInvocationKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class McpJsonRpcToolInvocationParserTest {
    private final McpJsonRpcToolInvocationParser parser = new McpJsonRpcToolInvocationParser(new ObjectMapper());

    @Test
    void parsesToolCall() {
        McpToolInvocation invocation = parser.parse("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"demo_tool"}}
                """.getBytes());

        assertEquals(McpToolInvocationKind.TOOL_CALL, invocation.kind());
        assertEquals("tools/call", invocation.method());
        assertEquals("demo_tool", invocation.toolName());
        assertEquals("demo_tool", invocation.actionName());
    }

    @Test
    void parsesToolsList() {
        McpToolInvocation invocation = parser.parse("""
                {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
                """.getBytes());

        assertEquals(McpToolInvocationKind.TOOLS_LIST, invocation.kind());
        assertEquals("tools/list", invocation.actionName());
        assertNull(invocation.toolName());
    }

    @Test
    void returnsUnknownForMalformedOrNonObjectBodies() {
        assertEquals(McpToolInvocationKind.UNKNOWN, parser.parse("not-json".getBytes()).kind());
        assertEquals(McpToolInvocationKind.UNKNOWN, parser.parse("[{}]".getBytes()).kind());
        assertEquals(McpToolInvocationKind.UNKNOWN, parser.parse(new byte[0]).kind());
    }
}
