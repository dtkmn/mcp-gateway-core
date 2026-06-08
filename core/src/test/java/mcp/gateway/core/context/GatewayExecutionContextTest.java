package mcp.gateway.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class GatewayExecutionContextTest {

    @Test
    void normalizesPrincipalWorkspaceAndCorrelation() {
        GatewayExecutionContext context = GatewayExecutionContext.of(" client-a ", " workspace-one ", " corr-1 ");

        assertEquals("client-a", context.principalId());
        assertEquals("workspace-one", context.workspaceId());
        assertEquals("corr-1", context.correlationId());
    }

    @Test
    void defaultsMissingPrincipalAndWorkspaceButNotCorrelation() {
        GatewayExecutionContext context = GatewayExecutionContext.of(" ", null, "\t");

        assertEquals(GatewayPrincipal.ANONYMOUS_ID, context.principalId());
        assertEquals(GatewayWorkspace.DEFAULT_ID, context.workspaceId());
        assertNull(context.correlationId());
    }

    @Test
    void acceptsNullNestedTypes() {
        GatewayExecutionContext context = new GatewayExecutionContext(null, null, null);

        assertEquals(GatewayPrincipal.ANONYMOUS_ID, context.principalId());
        assertEquals(GatewayWorkspace.DEFAULT_ID, context.workspaceId());
        assertNull(context.correlationId());
    }
}
