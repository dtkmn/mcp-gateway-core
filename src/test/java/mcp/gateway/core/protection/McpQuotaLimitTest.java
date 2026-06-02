package mcp.gateway.core.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class McpQuotaLimitTest {

    @Test
    void allowsWhenCountIsBelowLimit() {
        McpAbuseProtectionDecision decision = McpQuotaLimit.of(
                "workspace_quota_exceeded",
                "workspace_scan_jobs",
                1,
                2,
                30
        ).evaluate(McpAbuseProtectionContext.of(" tool ", " client ", " workspace "));

        assertTrue(decision.allowed());
        assertEquals("tool", decision.toolName());
        assertEquals("client", decision.clientId());
        assertEquals("workspace", decision.workspaceId());
    }

    @Test
    void rejectsWhenCountHasReachedLimit() {
        McpAbuseProtectionDecision decision = McpQuotaLimit.of(
                "workspace_quota_exceeded",
                "workspace_scan_jobs",
                2,
                2,
                30
        ).evaluate(McpAbuseProtectionContext.of("tool", "client", "workspace"));

        assertFalse(decision.allowed());
        assertEquals("workspace_quota_exceeded", decision.errorCode());
        assertEquals("workspace_scan_jobs", decision.reason());
        assertEquals(30L, decision.retryAfterSeconds());
    }

    @Test
    void normalizesInvalidLimitInputsFailClosed() {
        McpAbuseProtectionDecision decision = McpQuotaLimit.of(" ", " ", -1, -1, 0)
                .evaluate(null);

        assertFalse(decision.allowed());
        assertEquals("quota_exceeded", decision.errorCode());
        assertEquals("quota_limit", decision.reason());
        assertEquals(1L, decision.retryAfterSeconds());
    }
}
