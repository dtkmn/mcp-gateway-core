package mcp.gateway.core.policy;

/**
 * Raised when a runtime policy hook denies a tool call in enforce mode.
 */
public class ToolPolicyDeniedException extends RuntimeException {
    /**
     * Creates a denied-policy exception.
     *
     * @param message exception message
     */
    public ToolPolicyDeniedException(String message) {
        super(message);
    }
}
