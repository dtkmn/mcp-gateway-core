package mcp.gateway.core.policybundle;

import java.util.List;
import java.util.Objects;

/**
 * Final first-match policy bundle evaluation result.
 *
 * @param decision final allow/deny decision
 * @param source decision source
 * @param matchedRuleId matched rule id, or null when default was used
 * @param reason human-readable reason
 * @param defaultDecision bundle default decision
 * @param trace rule evaluation trace in evaluation order
 */
public record PolicyBundleEvaluationResult(PolicyBundleDecision decision,
                                           PolicyBundleDecisionSource source,
                                           String matchedRuleId,
                                           String reason,
                                           PolicyBundleDecision defaultDecision,
                                           List<PolicyBundleRuleEvaluation> trace) {

    /**
     * Creates an evaluation result.
     */
    public PolicyBundleEvaluationResult {
        decision = Objects.requireNonNull(decision, "decision must not be null");
        source = Objects.requireNonNull(source, "source must not be null");
        if (matchedRuleId != null && matchedRuleId.isBlank()) {
            matchedRuleId = null;
        }
        reason = reason == null || reason.isBlank() ? defaultReason(source) : reason.trim();
        defaultDecision = Objects.requireNonNull(defaultDecision, "defaultDecision must not be null");
        trace = List.copyOf(trace == null ? List.of() : trace);
    }

    private static String defaultReason(PolicyBundleDecisionSource source) {
        if (source == PolicyBundleDecisionSource.DEFAULT) {
            return "No enabled rule matched the request. Using bundle default decision.";
        }
        return "Matched policy rule.";
    }
}
