package mcp.gateway.core.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mcp.gateway.core.metadata.GatewayMetadataSnapshot;
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

    @Test
    void legacyConstructorAndFactoriesKeepOpaqueAndNestedValuesShallow() {
        Object opaque = new Object();
        List<String> nested = new ArrayList<>(List.of("before"));
        Map<String, Object> source = new LinkedHashMap<>();
        source.put(" opaque ", opaque);
        source.put(null, "dropped");
        source.put("dropped", null);
        source.put("nested", nested);
        List<ToolPolicyDecision> decisions = decisionsWith(source);

        source.clear();
        nested.add("after");

        for (ToolPolicyDecision decision : decisions) {
            assertEquals(List.of(" opaque ", "nested"), new ArrayList<>(decision.details().keySet()));
            assertSame(opaque, decision.details().get(" opaque "));
            assertSame(nested, decision.details().get("nested"));
            assertEquals(List.of("before", "after"), decision.details().get("nested"));
            assertThrows(UnsupportedOperationException.class, () -> decision.details().clear());
        }
    }

    @Test
    void constructorAndAllDetailFactoriesRetainExplicitNestedSnapshots() {
        Map<String, Object> check = new LinkedHashMap<>(Map.of("name", "scope"));
        List<Object> checks = new ArrayList<>(List.of(check));
        Map<String, Object> source = new LinkedHashMap<>(Map.of("checks", checks));
        List<ToolPolicyDecision> decisions = decisionsWith(GatewayMetadataSnapshot.copyOf(source));

        check.put("name", "changed");
        checks.clear();
        source.clear();

        for (ToolPolicyDecision decision : decisions) {
            assertEquals(Map.of("checks", List.of(Map.of("name", "scope"))), decision.details());
            List<?> retainedChecks = (List<?>) decision.details().get("checks");
            Map<?, ?> retainedCheck = (Map<?, ?>) retainedChecks.get(0);
            assertThrows(UnsupportedOperationException.class, () -> decision.details().clear());
            assertThrows(UnsupportedOperationException.class, retainedChecks::clear);
            assertThrows(UnsupportedOperationException.class, retainedCheck::clear);
        }
    }

    private static List<ToolPolicyDecision> decisionsWith(Map<String, Object> details) {
        return List.of(new ToolPolicyDecision(ToolPolicyOutcome.ALLOW, "reason", details),
                ToolPolicyDecision.allow("reason", details),
                ToolPolicyDecision.deny("reason", details),
                ToolPolicyDecision.abstain("reason", details));
    }
}
