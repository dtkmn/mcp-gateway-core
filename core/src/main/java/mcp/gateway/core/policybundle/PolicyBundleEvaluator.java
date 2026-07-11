package mcp.gateway.core.policybundle;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * First-match evaluator for MCP policy bundle rules.
 */
public final class PolicyBundleEvaluator {
    private static final String DEFAULT_REASON =
            "No enabled rule matched the request. Using bundle default decision.";

    private PolicyBundleEvaluator() {
    }

    /**
     * Evaluates rules in order and returns the first matching decision, or the
     * supplied default decision when no enabled rule matches.
     *
     * @param ruleset validated ruleset
     * @param request evaluation request
     * @return evaluation result
     */
    public static PolicyBundleEvaluationResult evaluate(PolicyBundleRuleset ruleset,
                                                        PolicyBundleEvaluationRequest request) {
        Objects.requireNonNull(ruleset, "ruleset must not be null");
        Objects.requireNonNull(request, "request must not be null");
        List<PolicyBundleRuleEvaluation> trace = new ArrayList<>();

        for (PolicyBundleRule rule : ruleset.rules()) {
            PolicyBundleRuleEvaluation evaluation = evaluateRule(rule, request);
            trace.add(evaluation);
            if (evaluation.matched()) {
                return new PolicyBundleEvaluationResult(
                        rule.decision(),
                        PolicyBundleDecisionSource.RULE,
                        rule.id(),
                        rule.reason(),
                        ruleset.defaultDecision(),
                        trace
                );
            }
        }

        return new PolicyBundleEvaluationResult(
                ruleset.defaultDecision(),
                PolicyBundleDecisionSource.DEFAULT,
                null,
                DEFAULT_REASON,
                ruleset.defaultDecision(),
                trace
        );
    }

    private static PolicyBundleRuleEvaluation evaluateRule(PolicyBundleRule rule,
                                                           PolicyBundleEvaluationRequest request) {
        List<String> matchedSelectors = new ArrayList<>();
        List<String> failedSelectors = new ArrayList<>();

        if (!rule.enabled()) {
            failedSelectors.add("disabled");
            return PolicyBundleRuleEvaluation.of(rule, false, matchedSelectors, failedSelectors);
        }

        PolicyBundleMatch match = rule.match();
        if (!match.tools().isEmpty()) {
            if (match.tools().contains(request.toolName())) {
                matchedSelectors.add("tools");
            } else {
                failedSelectors.add("tools");
            }
        }

        if (!match.hosts().isEmpty()) {
            if (request.normalizedHost() != null && match.hosts().stream()
                    .anyMatch(pattern -> hostMatches(request.normalizedHost(), pattern))) {
                matchedSelectors.add("hosts");
            } else {
                failedSelectors.add("hosts");
            }
        }

        if (!match.timeWindows().isEmpty()) {
            if (match.timeWindows().stream().anyMatch(window -> window.matches(request.bundleTime()))) {
                matchedSelectors.add("timeWindows");
            } else {
                failedSelectors.add("timeWindows");
            }
        }

        return PolicyBundleRuleEvaluation.of(rule, failedSelectors.isEmpty(), matchedSelectors, failedSelectors);
    }

    private static boolean hostMatches(String normalizedHost, String pattern) {
        if (pattern.startsWith("*.")) {
            String suffix = pattern.substring(1);
            if (!normalizedHost.endsWith(suffix) || normalizedHost.length() <= suffix.length()) {
                return false;
            }
            String prefix = normalizedHost.substring(0, normalizedHost.length() - suffix.length());
            return !prefix.startsWith(".")
                    && !prefix.endsWith(".")
                    && !prefix.contains("..");
        }
        return normalizedHost.equals(pattern);
    }
}
