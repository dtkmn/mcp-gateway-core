package mcp.gateway.core.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import mcp.gateway.core.context.GatewayToolExecutionContext;
import mcp.gateway.core.invocation.McpToolInvocation;
import mcp.gateway.core.tool.McpToolSurface;
import org.junit.jupiter.api.Test;

class McpToolAuthorizerTest {

    @Test
    void authorizesMappedToolCalls() {
        McpToolAuthorizer authorizer = authorizer();

        ToolAuthorizationDecision decision =
                authorizer.authorizeToolCall("demo_tool", List.of("demo:execute"), false, true);

        assertTrue(decision.allowed());
        assertTrue(decision.mapped());
        assertEquals("demo_tool", decision.actionName());
        assertEquals(List.of("demo:execute"), decision.requiredScopes());
        assertEquals(List.of(), decision.missingScopes());
    }

    @Test
    void deniesMissingScopesAndControlsWildcardExplicitly() {
        McpToolAuthorizer authorizer = authorizer();

        ToolAuthorizationDecision missing =
                authorizer.authorizeToolCall("demo_tool", List.of("demo:read"), true, true);
        ToolAuthorizationDecision wildcardDenied =
                authorizer.authorizeToolCall("demo_tool", List.of("*"), false, true);
        ToolAuthorizationDecision wildcardAllowed =
                authorizer.authorizeToolCall("demo_tool", List.of("*"), true, true);

        assertFalse(missing.allowed());
        assertEquals(List.of("demo:execute"), missing.missingScopes());
        assertFalse(wildcardDenied.allowed());
        assertTrue(wildcardAllowed.allowed());
    }

    @Test
    void unmappedToolCallsFailClosedEvenWhenWildcardIsGranted() {
        McpToolAuthorizer authorizer = authorizer();

        ToolAuthorizationDecision decision =
                authorizer.authorizeToolCall("missing_tool", List.of("*"), true, true);

        assertFalse(decision.allowed());
        assertFalse(decision.mapped());
        assertEquals("missing_tool", decision.actionName());
        assertEquals(List.of(), decision.requiredScopes());
    }

    @Test
    void authorizesToolsListWithSyntheticGatewayAction() {
        McpToolAuthorizer authorizer = authorizer();

        ToolAuthorizationDecision denied =
                authorizer.authorizeToolsList(List.of("demo:execute"), true, true);
        ToolAuthorizationDecision allowed =
                authorizer.authorizeToolsList(List.of("mcp:tools:list"), true, true);

        assertFalse(denied.allowed());
        assertEquals(McpToolAuthorizer.TOOLS_LIST_ACTION, denied.actionName());
        assertEquals(List.of("mcp:tools:list"), denied.requiredScopes());
        assertTrue(allowed.allowed());
    }

    @Test
    void authorizesGatewayToolExecutionContexts() {
        McpToolAuthorizer authorizer = authorizer();

        GatewayToolExecutionContext toolCall = GatewayToolExecutionContext.of(
                "client",
                "workspace",
                "corr",
                McpToolInvocation.fromJsonRpc(McpToolInvocation.METHOD_TOOLS_CALL, "demo_tool"),
                null
        );
        GatewayToolExecutionContext toolsList = GatewayToolExecutionContext.of(
                "client",
                "workspace",
                "corr",
                McpToolInvocation.fromJsonRpc(McpToolInvocation.METHOD_TOOLS_LIST, null),
                null
        );

        assertTrue(authorizer.authorize(toolCall, List.of("demo:execute"), true, true).allowed());
        assertTrue(authorizer.authorize(toolsList, List.of("mcp:tools:list"), true, true).allowed());
    }

    @Test
    void nonAuthorizableContextsFailClosed() {
        McpToolAuthorizer authorizer = authorizer();
        GatewayToolExecutionContext context = GatewayToolExecutionContext.of(
                "client",
                "workspace",
                "corr",
                McpToolInvocation.fromJsonRpc("initialize", null),
                null
        );

        ToolAuthorizationDecision decision = authorizer.authorize(context, List.of("*"), true, true);

        assertFalse(decision.allowed());
        assertFalse(decision.mapped());
        assertEquals(McpToolAuthorizer.UNKNOWN_ACTION, decision.actionName());
    }

    @Test
    void disabledAuthorizationAllowsMappedActionsButNotUnmappedActions() {
        McpToolAuthorizer authorizer = authorizer();

        ToolAuthorizationDecision mapped =
                authorizer.authorizeToolCall("demo_tool", List.of(), false, false);
        ToolAuthorizationDecision unmapped =
                authorizer.authorizeToolCall("missing_tool", List.of("*"), true, false);

        assertTrue(mapped.allowed());
        assertTrue(mapped.mapped());
        assertEquals(List.of("demo:execute"), mapped.requiredScopes());
        assertFalse(unmapped.allowed());
        assertFalse(unmapped.mapped());
    }

    @Test
    void rejectsMissingToolsListRequirement() {
        McpToolAccessRegistry registry = McpToolAccessRegistry.of(List.of(
                McpToolAccessRule.builder("demo_tool", McpToolSurface.GUIDED)
                        .requiredScope("demo:execute")
                        .build()
        ));

        assertThrows(IllegalArgumentException.class, () -> McpToolAuthorizer.of(registry, List.of()));
    }

    private McpToolAuthorizer authorizer() {
        McpToolAccessRegistry registry = McpToolAccessRegistry.of(List.of(
                McpToolAccessRule.builder("demo_tool", McpToolSurface.GUIDED)
                        .requiredScope("demo:execute")
                        .build()
        ));
        return McpToolAuthorizer.of(registry, List.of("mcp:tools:list"));
    }
}
