package mcp.gateway.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import mcp.gateway.core.invocation.McpToolInvocation;
import mcp.gateway.core.invocation.McpToolInvocationKind;
import mcp.gateway.core.policy.ToolPolicyEvaluationContext;
import mcp.gateway.core.protection.McpAbuseProtectionContext;
import org.junit.jupiter.api.Test;

class GatewayToolExecutionContextTest {

    @Test
    void exposesExecutionAndInvocationValues() {
        GatewayToolExecutionContext context = GatewayToolExecutionContext.of(
                "client-a",
                "workspace-one",
                "corr-1",
                McpToolInvocation.fromJsonRpc("tools/call", "scan_tool"),
                " https://example.test "
        );

        assertEquals("client-a", context.principalId());
        assertEquals("workspace-one", context.workspaceId());
        assertEquals("corr-1", context.correlationId());
        assertEquals("tools/call", context.method());
        assertEquals("scan_tool", context.toolName());
        assertEquals("scan_tool", context.actionName());
        assertEquals("https://example.test", context.target());
    }

    @Test
    void defaultsNullInvocationToUnknown() {
        GatewayToolExecutionContext context = GatewayToolExecutionContext.of(null, null, null);

        assertEquals(McpToolInvocationKind.UNKNOWN, context.invocation().kind());
        assertEquals(GatewayPrincipal.ANONYMOUS_ID, context.principalId());
        assertEquals(GatewayWorkspace.DEFAULT_ID, context.workspaceId());
        assertNull(context.correlationId());
        assertNull(context.actionName());
    }

    @Test
    void feedsPolicyAndProtectionContexts() {
        GatewayToolExecutionContext context = GatewayToolExecutionContext.of(
                "client-a",
                "workspace-one",
                "corr-1",
                McpToolInvocation.fromJsonRpc("tools/call", "scan_tool"),
                "https://example.test"
        );

        ToolPolicyEvaluationContext policyContext = ToolPolicyEvaluationContext.from(context);
        McpAbuseProtectionContext protectionContext = McpAbuseProtectionContext.from(context);

        assertEquals("scan_tool", policyContext.toolName());
        assertEquals("https://example.test", policyContext.target());
        assertEquals("corr-1", policyContext.correlationId());
        assertEquals("scan_tool", protectionContext.toolName());
        assertEquals("client-a", protectionContext.clientId());
        assertEquals("workspace-one", protectionContext.workspaceId());
    }
}
