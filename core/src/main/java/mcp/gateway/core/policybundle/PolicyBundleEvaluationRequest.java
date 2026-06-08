package mcp.gateway.core.policybundle;

import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.Objects;

/**
 * Normalized input for first-match policy bundle evaluation.
 *
 * @param toolName exact MCP tool/action name
 * @param normalizedHost lower-case host, or null for hostless calls
 * @param bundleTime evaluation time in the bundle timezone
 */
public record PolicyBundleEvaluationRequest(String toolName,
                                            String normalizedHost,
                                            ZonedDateTime bundleTime) {

    /**
     * Creates an evaluation request.
     */
    public PolicyBundleEvaluationRequest {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must not be blank");
        }
        toolName = toolName.trim().toLowerCase(Locale.ROOT);
        if (normalizedHost != null) {
            normalizedHost = normalizedHost.isBlank() ? null : normalizedHost.trim().toLowerCase(Locale.ROOT);
        }
        bundleTime = Objects.requireNonNull(bundleTime, "bundleTime must not be null");
    }
}
