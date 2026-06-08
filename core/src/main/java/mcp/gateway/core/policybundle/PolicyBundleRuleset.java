package mcp.gateway.core.policybundle;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Ordered policy bundle rules plus the fallback decision used when no rule matches.
 *
 * @param defaultDecision fallback decision
 * @param rules ordered first-match rule list
 */
public record PolicyBundleRuleset(PolicyBundleDecision defaultDecision,
                                  List<PolicyBundleRule> rules) {
    /**
     * Maximum rules accepted in one ruleset.
     */
    public static final int MAX_RULES = 50;

    /**
     * Creates a validated ordered ruleset.
     */
    public PolicyBundleRuleset {
        defaultDecision = Objects.requireNonNull(defaultDecision, "defaultDecision must not be null");
        rules = List.copyOf(rules == null ? List.of() : rules);
        if (rules.isEmpty()) {
            throw new IllegalArgumentException("policy bundle rules must not be empty");
        }
        if (rules.size() > MAX_RULES) {
            throw new IllegalArgumentException("policy bundle rules must not contain more than " + MAX_RULES + " rules");
        }

        Set<String> seenRuleIds = new LinkedHashSet<>();
        for (PolicyBundleRule rule : rules) {
            String ruleId = Objects.requireNonNull(rule, "rule must not be null").id();
            if (!seenRuleIds.add(ruleId)) {
                throw new IllegalArgumentException("policy bundle rule id '" + ruleId + "' must be unique");
            }
        }
    }

    /**
     * Creates a validated ordered ruleset.
     *
     * @param defaultDecision fallback decision
     * @param rules ordered rules
     * @return ruleset
     */
    public static PolicyBundleRuleset of(PolicyBundleDecision defaultDecision,
                                         Collection<PolicyBundleRule> rules) {
        return new PolicyBundleRuleset(
                defaultDecision,
                rules == null ? List.of() : List.copyOf(rules)
        );
    }
}
