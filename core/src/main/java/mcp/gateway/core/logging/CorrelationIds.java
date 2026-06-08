package mcp.gateway.core.logging;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Helpers for resolving and sanitizing request correlation identifiers.
 */
public final class CorrelationIds {
    /** Preferred HTTP header for caller-supplied correlation identifiers. */
    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    /** Legacy HTTP header accepted as a fallback correlation identifier. */
    public static final String LEGACY_REQUEST_ID_HEADER = "X-Request-Id";

    private static final int MAX_CORRELATION_ID_LENGTH = 128;
    private static final Pattern SAFE_CORRELATION_ID = Pattern.compile("^[A-Za-z0-9._:/-]{1,128}$");

    private CorrelationIds() {
    }

    /**
     * Resolves a safe correlation identifier from preferred and legacy header values.
     *
     * @param correlationIdHeader preferred correlation header value
     * @param legacyRequestIdHeader legacy request identifier header value
     * @return sanitized caller-provided value, or a generated UUID when unavailable
     */
    public static String resolve(String correlationIdHeader, String legacyRequestIdHeader) {
        String normalized = sanitize(correlationIdHeader);
        if (normalized != null) {
            return normalized;
        }

        normalized = sanitize(legacyRequestIdHeader);
        if (normalized != null) {
            return normalized;
        }

        return UUID.randomUUID().toString();
    }

    /**
     * Sanitizes a candidate correlation identifier.
     *
     * @param candidate candidate identifier
     * @return safe identifier, or {@code null} when unsafe or blank
     */
    public static String sanitize(String candidate) {
        if (candidate == null) {
            return null;
        }

        String normalized = candidate.trim();
        if (normalized.isEmpty() || normalized.length() > MAX_CORRELATION_ID_LENGTH) {
            return null;
        }

        return SAFE_CORRELATION_ID.matcher(normalized).matches() ? normalized : null;
    }
}
