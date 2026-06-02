package mcp.gateway.core.tool;

import java.util.Locale;

/**
 * A normalized MCP tool surface label.
 *
 * @param value stable surface identifier
 */
public record McpToolSurface(String value) {
    /**
     * Conventional guided tool surface.
     */
    public static final McpToolSurface GUIDED = new McpToolSurface("guided");

    /**
     * Conventional expert or expanded tool surface.
     */
    public static final McpToolSurface EXPERT = new McpToolSurface("expert");

    /**
     * Creates and normalizes a surface label.
     */
    public McpToolSurface {
        value = normalize(value);
    }

    /**
     * Creates a normalized surface label.
     *
     * @param value surface identifier
     * @return normalized surface
     */
    public static McpToolSurface of(String value) {
        return new McpToolSurface(value);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("tool surface must not be blank");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            boolean valid = character >= 'a' && character <= 'z'
                    || character >= '0' && character <= '9'
                    || character == '.'
                    || character == '-'
                    || character == '_';
            if (!valid) {
                throw new IllegalArgumentException("tool surface contains unsupported character: " + value);
            }
        }
        return normalized;
    }
}
