package mcp.gateway.core.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class McpAbuseProtectionContextTest {

    @Test
    void trimsContextFields() {
        McpAbuseProtectionContext context = McpAbuseProtectionContext.of(" tool ", " client ", " workspace ");

        assertEquals("tool", context.toolName());
        assertEquals("client", context.clientId());
        assertEquals("workspace", context.workspaceId());
    }

    @Test
    void treatsBlankFieldsAsUnknown() {
        McpAbuseProtectionContext context = McpAbuseProtectionContext.of(" ", null, "\t");

        assertNull(context.toolName());
        assertNull(context.clientId());
        assertNull(context.workspaceId());
    }
}
