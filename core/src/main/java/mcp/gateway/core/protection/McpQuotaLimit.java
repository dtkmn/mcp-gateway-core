package mcp.gateway.core.protection;

/**
 * Generic count-based quota limit for MCP gateway protection decisions.
 *
 * @param errorCode machine-readable rejection code
 * @param reason human-readable rejection reason
 * @param currentCount current observed count
 * @param maxAllowed maximum allowed count before rejection
 * @param retryAfterSeconds retry delay for rejected requests
 */
public record McpQuotaLimit(String errorCode,
                            String reason,
                            int currentCount,
                            int maxAllowed,
                            long retryAfterSeconds) {
    /**
     * Creates a normalized quota limit.
     */
    public McpQuotaLimit {
        errorCode = normalize(errorCode, "quota_exceeded");
        reason = normalize(reason, "quota_limit");
        currentCount = Math.max(0, currentCount);
        maxAllowed = Math.max(0, maxAllowed);
        retryAfterSeconds = Math.max(1L, retryAfterSeconds);
    }

    /**
     * Creates a normalized quota limit.
     *
     * @param errorCode machine-readable rejection code
     * @param reason human-readable rejection reason
     * @param currentCount current observed count
     * @param maxAllowed maximum allowed count before rejection
     * @param retryAfterSeconds retry delay for rejected requests
     * @return quota limit
     */
    public static McpQuotaLimit of(String errorCode,
                                   String reason,
                                   int currentCount,
                                   int maxAllowed,
                                   long retryAfterSeconds) {
        return new McpQuotaLimit(errorCode, reason, currentCount, maxAllowed, retryAfterSeconds);
    }

    /**
     * Evaluates the quota limit for a protection context.
     *
     * @param context protection context
     * @return allow when under limit; reject when current count has reached or exceeded the limit
     */
    public McpAbuseProtectionDecision evaluate(McpAbuseProtectionContext context) {
        McpAbuseProtectionContext normalizedContext =
                context == null ? McpAbuseProtectionContext.of(null, null, null) : context;
        if (currentCount >= maxAllowed) {
            return McpAbuseProtectionDecision.reject(errorCode, reason, normalizedContext, retryAfterSeconds);
        }
        return McpAbuseProtectionDecision.allow(normalizedContext);
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
