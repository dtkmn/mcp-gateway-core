package mcp.gateway.core.policybundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolicyBundleEvaluatorTest {

    @Test
    void returnsFirstMatchingRuleDecisionAndTrace() {
        PolicyBundleRule first = rule(
                "deny-prod",
                PolicyBundleDecision.DENY,
                PolicyBundleMatch.of(
                        List.of("demo_attack_start"),
                        List.of("prod.example.com"),
                        List.of()
                )
        );
        PolicyBundleRule second = rule(
                "allow-sandbox",
                PolicyBundleDecision.ALLOW,
                PolicyBundleMatch.of(
                        List.of("demo_attack_start"),
                        List.of("api.sandbox.example.com"),
                        List.of(staffedWindow())
                )
        );

        PolicyBundleEvaluationResult result = PolicyBundleEvaluator.evaluate(
                PolicyBundleRuleset.of(PolicyBundleDecision.DENY, List.of(first, second)),
                request("demo_attack_start", "api.sandbox.example.com", "2026-06-02T01:00:00Z")
        );

        assertEquals(PolicyBundleDecision.ALLOW, result.decision());
        assertEquals(PolicyBundleDecisionSource.RULE, result.source());
        assertEquals("allow-sandbox", result.matchedRuleId());
        assertEquals(2, result.trace().size());
        assertFalse(result.trace().get(0).matched());
        assertEquals(List.of("tools"), result.trace().get(0).matchedSelectors());
        assertEquals(List.of("hosts"), result.trace().get(0).failedSelectors());
        assertTrue(result.trace().get(1).matched());
        assertEquals(List.of("tools", "hosts", "timeWindows"), result.trace().get(1).matchedSelectors());
    }

    @Test
    void evaluatesValidatedRuleset() {
        PolicyBundleRule rule = rule(
                "allow-sandbox",
                PolicyBundleDecision.ALLOW,
                PolicyBundleMatch.of(List.of("demo_attack_start"), List.of("api.sandbox.example.com"), List.of())
        );

        PolicyBundleEvaluationResult result = PolicyBundleEvaluator.evaluate(
                PolicyBundleRuleset.of(PolicyBundleDecision.DENY, List.of(rule)),
                request("demo_attack_start", "api.sandbox.example.com", "2026-06-02T01:00:00Z")
        );

        assertEquals(PolicyBundleDecision.ALLOW, result.decision());
        assertEquals("allow-sandbox", result.matchedRuleId());
    }

    @Test
    void returnsDefaultDecisionWhenNoRuleMatches() {
        PolicyBundleEvaluationResult result = PolicyBundleEvaluator.evaluate(
                PolicyBundleRuleset.of(
                        PolicyBundleDecision.DENY,
                        List.of(rule(
                                "allow-sandbox",
                                PolicyBundleDecision.ALLOW,
                                PolicyBundleMatch.of(List.of("demo_report_read"), List.of("sandbox.example.com"), List.of())
                        ))
                ),
                request("demo_attack_start", "prod.example.com", "2026-06-02T01:00:00Z")
        );

        assertEquals(PolicyBundleDecision.DENY, result.decision());
        assertEquals(PolicyBundleDecisionSource.DEFAULT, result.source());
        assertEquals(null, result.matchedRuleId());
        assertEquals("No enabled rule matched the request. Using bundle default decision.", result.reason());
    }

    @Test
    void disabledRulesDoNotMatch() {
        PolicyBundleRule disabled = new PolicyBundleRule(
                "disabled-rule",
                PolicyBundleDecision.ALLOW,
                "disabled",
                false,
                PolicyBundleMatch.of(List.of("demo_attack_start"), List.of(), List.of())
        );

        PolicyBundleEvaluationResult result = PolicyBundleEvaluator.evaluate(
                PolicyBundleRuleset.of(PolicyBundleDecision.DENY, List.of(disabled)),
                request("demo_attack_start", null, "2026-06-02T01:00:00Z")
        );

        assertEquals(PolicyBundleDecision.DENY, result.decision());
        assertEquals(List.of("disabled"), result.trace().get(0).failedSelectors());
    }

    @Test
    void wildcardHostsAndOvernightWindowsMatch() {
        PolicyBundleTimeWindow overnight = PolicyBundleTimeWindow.of(
                List.of(DayOfWeek.TUESDAY),
                LocalTime.parse("22:00"),
                LocalTime.parse("02:00")
        );
        PolicyBundleRule rule = rule(
                "allow-overnight",
                PolicyBundleDecision.ALLOW,
                PolicyBundleMatch.of(List.of("demo_attack_start"), List.of("*.example.com"), List.of(overnight))
        );

        PolicyBundleEvaluationResult result = PolicyBundleEvaluator.evaluate(
                PolicyBundleRuleset.of(PolicyBundleDecision.DENY, List.of(rule)),
                request("demo_attack_start", "api.example.com", "2026-06-02T23:30:00Z")
        );

        assertEquals(PolicyBundleDecision.ALLOW, result.decision());
        assertTrue(result.trace().get(0).matched());
    }

    @Test
    void rejectsInvalidCoreContracts() {
        assertThrows(IllegalArgumentException.class, () -> PolicyBundleDecision.fromWireValue("maybe"));
        assertThrows(IllegalArgumentException.class, () -> PolicyBundleTimeWindow.of(
                List.of(DayOfWeek.MONDAY),
                LocalTime.NOON,
                LocalTime.NOON
        ));
        assertThrows(IllegalArgumentException.class, () -> PolicyBundleMatch.of(List.of(), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> PolicyBundleMatch.of(List.of(" "), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> PolicyBundleMatch.of(List.of(" "), List.of("sandbox.example.com"), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> PolicyBundleMatch.of(List.of("demo_attack_start"), java.util.Arrays.asList("sandbox.example.com", null), List.of()));
        assertThrows(IllegalArgumentException.class, () -> PolicyBundleRuleset.of(PolicyBundleDecision.DENY, List.of()));
        assertThrows(IllegalArgumentException.class, () -> PolicyBundleRuleset.of(
                PolicyBundleDecision.DENY,
                List.of(
                        rule("duplicate-rule", PolicyBundleDecision.ALLOW, PolicyBundleMatch.of(List.of("demo_attack_start"), List.of(), List.of())),
                        rule("duplicate-rule", PolicyBundleDecision.DENY, PolicyBundleMatch.of(List.of("demo_report_read"), List.of(), List.of()))
                )
        ));
        java.util.List<PolicyBundleRule> tooManyRules = new java.util.ArrayList<>();
        for (int index = 0; index < PolicyBundleRuleset.MAX_RULES + 1; index++) {
            tooManyRules.add(rule(
                    "rule-" + index,
                    PolicyBundleDecision.ALLOW,
                    PolicyBundleMatch.of(List.of("demo_attack_start"), List.of("host-" + index + ".example.com"), List.of())
            ));
        }
        assertThrows(IllegalArgumentException.class,
                () -> PolicyBundleRuleset.of(PolicyBundleDecision.DENY, tooManyRules));
        assertThrows(IllegalArgumentException.class, () -> new PolicyBundleEvaluationRequest(" ", null, ZonedDateTime.now()));
    }

    private PolicyBundleRule rule(String id, PolicyBundleDecision decision, PolicyBundleMatch match) {
        return new PolicyBundleRule(id, decision, id + " reason", true, match);
    }

    private PolicyBundleTimeWindow staffedWindow() {
        return PolicyBundleTimeWindow.of(
                List.of(DayOfWeek.TUESDAY),
                LocalTime.parse("00:00"),
                LocalTime.parse("09:00")
        );
    }

    private PolicyBundleEvaluationRequest request(String tool, String host, String instant) {
        return new PolicyBundleEvaluationRequest(
                tool,
                host,
                ZonedDateTime.ofInstant(java.time.Instant.parse(instant), ZoneOffset.UTC)
        );
    }
}
