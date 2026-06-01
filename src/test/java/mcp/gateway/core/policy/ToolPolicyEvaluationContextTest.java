package mcp.gateway.core.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ToolPolicyEvaluationContextTest {

    @Test
    void trimsValues() {
        ToolPolicyEvaluationContext context =
                new ToolPolicyEvaluationContext(" tool ", " target ", " corr ");

        assertEquals("tool", context.toolName());
        assertEquals("target", context.target());
        assertEquals("corr", context.correlationId());
    }

    @Test
    void blankValuesBecomeNull() {
        ToolPolicyEvaluationContext context =
                new ToolPolicyEvaluationContext(" ", " ", " ");

        assertNull(context.toolName());
        assertNull(context.target());
        assertNull(context.correlationId());
    }
}
