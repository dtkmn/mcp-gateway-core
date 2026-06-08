package mcp.gateway.core.policybundle;

import java.util.Collection;
import java.util.List;

/**
 * Trace entry for one rule evaluation.
 *
 * @param ruleId evaluated rule id
 * @param decision rule decision
 * @param enabled whether the rule was enabled
 * @param matched whether every configured selector matched
 * @param matchedSelectors selector names that matched
 * @param failedSelectors selector names that failed
 * @param reason rule reason
 */
public record PolicyBundleRuleEvaluation(String ruleId,
                                         PolicyBundleDecision decision,
                                         boolean enabled,
                                         boolean matched,
                                         List<String> matchedSelectors,
                                         List<String> failedSelectors,
                                         String reason) {

    /**
     * Creates a trace entry.
     */
    public PolicyBundleRuleEvaluation {
        if (ruleId == null || ruleId.isBlank()) {
            throw new IllegalArgumentException("rule id must not be blank");
        }
        matchedSelectors = List.copyOf(matchedSelectors == null ? List.of() : matchedSelectors);
        failedSelectors = List.copyOf(failedSelectors == null ? List.of() : failedSelectors);
        reason = reason == null ? "" : reason.trim();
    }

    static PolicyBundleRuleEvaluation of(PolicyBundleRule rule,
                                         boolean matched,
                                         Collection<String> matchedSelectors,
                                         Collection<String> failedSelectors) {
        return new PolicyBundleRuleEvaluation(
                rule.id(),
                rule.decision(),
                rule.enabled(),
                matched,
                List.copyOf(matchedSelectors),
                List.copyOf(failedSelectors),
                rule.reason()
        );
    }
}
