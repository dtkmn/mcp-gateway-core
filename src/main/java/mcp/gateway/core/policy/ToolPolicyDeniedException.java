package mcp.gateway.core.policy;

/**
 * Raised when a runtime policy hook denies a tool call in enforce mode.
 */
public class ToolPolicyDeniedException extends RuntimeException {
    public ToolPolicyDeniedException(String message) {
        super(message);
    }
}
