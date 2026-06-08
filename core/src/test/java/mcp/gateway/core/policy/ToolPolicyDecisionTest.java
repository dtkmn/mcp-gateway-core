package mcp.gateway.core.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolPolicyDecisionTest {

    @Test
    void createsAllowDenyAndAbstainDecisions() {
        assertTrue(ToolPolicyDecision.allow("ok").allowed());
        assertTrue(ToolPolicyDecision.deny("blocked").denied());
        assertTrue(ToolPolicyDecision.abstain("not configured").abstained());
    }

    @Test
    void nullOutcomeFailsClosedAsDeny() {
        ToolPolicyDecision decision = new ToolPolicyDecision(null, "invalid", Map.of());

        assertTrue(decision.denied());
        assertFalse(decision.allowed());
        assertEquals(ToolPolicyOutcome.DENY, decision.outcome());
    }

    @Test
    void trimsReasonAndDropsNullDetails() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("policyProvider", "basic");
        details.put("dropped", null);
        details.put(null, "dropped");

        ToolPolicyDecision decision = ToolPolicyDecision.allow(" approved ", details);

        assertEquals("approved", decision.reason());
        assertEquals(Map.of("policyProvider", "basic"), decision.details());
    }

    @Test
    void detailsAreImmutable() {
        ToolPolicyDecision decision = ToolPolicyDecision.deny("blocked", Map.of("key", "value"));

        assertThrows(UnsupportedOperationException.class, () -> decision.details().put("another", "value"));
    }
}
