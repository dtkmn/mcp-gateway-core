package mcp.gateway.core.policybundle;

import java.util.Objects;

/**
 * One first-match policy bundle rule.
 *
 * @param id rule id
 * @param decision rule decision
 * @param reason human-readable reason
 * @param enabled whether this rule participates in matching
 * @param match selector set
 */
public record PolicyBundleRule(String id,
                               PolicyBundleDecision decision,
                               String reason,
                               boolean enabled,
                               PolicyBundleMatch match) {

    /**
     * Creates a policy rule.
     */
    public PolicyBundleRule {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("rule id must not be blank");
        }
        id = id.trim();
        decision = Objects.requireNonNull(decision, "decision must not be null");
        reason = reason == null ? "" : reason.trim();
        match = Objects.requireNonNull(match, "match must not be null");
    }
}
