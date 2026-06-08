package mcp.gateway.core.tool;

import java.util.Locale;

/**
 * A normalized capability label attached to an MCP tool.
 *
 * @param value stable capability identifier
 */
public record McpToolCapability(String value) {

    /**
     * Creates and normalizes a capability label.
     */
    public McpToolCapability {
        value = normalize(value);
    }

    /**
     * Creates a normalized capability label.
     *
     * @param value capability identifier
     * @return normalized capability
     */
    public static McpToolCapability of(String value) {
        return new McpToolCapability(value);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("tool capability must not be blank");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            boolean valid = character >= 'a' && character <= 'z'
                    || character >= '0' && character <= '9'
                    || character == '.'
                    || character == ':'
                    || character == '-'
                    || character == '_';
            if (!valid) {
                throw new IllegalArgumentException("tool capability contains unsupported character: " + value);
            }
        }
        return normalized;
    }
}
