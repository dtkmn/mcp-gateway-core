package mcp.gateway.core.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class McpAbuseProtectionDecisionTest {

    @Test
    void normalizesRejectedDecisionForAdapterSafeOutput() {
        McpAbuseProtectionDecision decision = new McpAbuseProtectionDecision(
                false,
                " ",
                null,
                " tool ",
                " client ",
                " workspace ",
                -1L
        );

        assertEquals("protection_rejected", decision.errorCode());
        assertEquals("protection_rejected", decision.reason());
        assertEquals("tool", decision.toolName());
        assertEquals("client", decision.clientId());
        assertEquals("workspace", decision.workspaceId());
        assertEquals(1L, decision.retryAfterSeconds());
    }

    @Test
    void removesContradictoryRejectionMetadataFromAllowedDecision() {
        McpAbuseProtectionDecision decision = new McpAbuseProtectionDecision(
                true,
                "rate_limited",
                "slow down",
                "tool",
                "client",
                "workspace",
                30L
        );

        assertNull(decision.errorCode());
        assertNull(decision.reason());
        assertEquals(0L, decision.retryAfterSeconds());
    }
}
