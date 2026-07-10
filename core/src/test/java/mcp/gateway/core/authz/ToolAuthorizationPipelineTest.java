package mcp.gateway.core.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ToolAuthorizationPipelineTest {

    @Test
    void allowsWhenAllRequiredScopesAreGranted() {
        ToolAuthorizationDecision decision = ToolAuthorizationPipeline.evaluate(
                ToolAuthorizationRequest.of("demo_tool", List.of(" TOOL.Read "), false),
                ToolAuthorizationRequirement.of("demo_tool", List.of("tool.read"))
        );

        assertTrue(decision.allowed());
        assertTrue(decision.mapped());
        assertEquals(List.of("tool.read"), decision.grantedScopes());
        assertEquals(List.of(), decision.missingScopes());
    }

    @Test
    void deniesMissingScopes() {
        ToolAuthorizationDecision decision = ToolAuthorizationPipeline.evaluate(
                ToolAuthorizationRequest.of("demo_tool", List.of("tool.read"), false),
                ToolAuthorizationRequirement.of("demo_tool", List.of("tool.write"))
        );

        assertFalse(decision.allowed());
        assertTrue(decision.mapped());
        assertEquals(List.of("tool.write"), decision.missingScopes());
    }

    @Test
    void wildcardIsExplicitlyControlled() {
        ToolAuthorizationDecision allowed = ToolAuthorizationPipeline.evaluate(
                ToolAuthorizationRequest.of("demo_tool", List.of("*"), true),
                ToolAuthorizationRequirement.of("demo_tool", List.of("tool.write"))
        );
        ToolAuthorizationDecision denied = ToolAuthorizationPipeline.evaluate(
                ToolAuthorizationRequest.of("demo_tool", List.of("*"), false),
                ToolAuthorizationRequirement.of("demo_tool", List.of("tool.write"))
        );

        assertTrue(allowed.allowed());
        assertFalse(denied.allowed());
        assertEquals(List.of("tool.write"), denied.missingScopes());
    }

    @Test
    void unmappedActionsFailClosed() {
        ToolAuthorizationDecision decision = ToolAuthorizationPipeline.evaluate(
                ToolAuthorizationRequest.of("demo_tool", List.of("*"), true),
                null
        );

        assertFalse(decision.allowed());
        assertFalse(decision.mapped());
        assertEquals(List.of(), decision.requiredScopes());
    }

    @Test
    void emptyRequirementsAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ToolAuthorizationRequirement.of("demo_tool", List.of(" ", ""))
        );
    }

    @Test
    void mismatchedRequirementIsRejected() {
        ToolAuthorizationRequest request = ToolAuthorizationRequest.of("demo_tool", List.of("*"), true);
        ToolAuthorizationRequirement requirement = ToolAuthorizationRequirement.of("other_tool", List.of("tool.read"));

        assertThrows(IllegalArgumentException.class, () -> ToolAuthorizationPipeline.evaluate(request, requirement));
    }

    @Test
    void scopesAreNormalizedAndDeduplicated() {
        ToolAuthorizationRequest request = ToolAuthorizationRequest.of(
                "demo_tool",
                List.of("Tool.Read", " tool.read ", "", "TOOL.WRITE"),
                false
        );

        assertEquals(List.of("tool.read", "tool.write"), request.grantedScopes());
    }

    @Test
    void configuredRequirementsRejectUnsafeOauthScopeTokens() {
        assertThrows(IllegalArgumentException.class,
                () -> ToolAuthorizationRequirement.of("demo_tool", List.of("tool.read write")));
        assertThrows(IllegalArgumentException.class,
                () -> ToolAuthorizationRequirement.of("demo_tool", List.of("tool.\"read")));
        assertThrows(IllegalArgumentException.class,
                () -> ToolAuthorizationRequirement.of("demo_tool", List.of("tool\\read")));
        assertThrows(IllegalArgumentException.class,
                () -> ToolAuthorizationRequirement.of("demo_tool", List.of("tøøl.read")));

        ToolAuthorizationRequirement requirement = ToolAuthorizationRequirement.of(
                "demo_tool",
                List.of("urn:example/tool.read")
        );
        assertEquals(List.of("urn:example/tool.read"), requirement.requiredScopes());
    }

    @Test
    void grantedScopeNormalizationRemainsTolerant() {
        ToolAuthorizationRequest request = ToolAuthorizationRequest.of(
                "demo_tool",
                List.of(" external role ", "tool.read"),
                false
        );

        assertEquals(List.of("external role", "tool.read"), request.grantedScopes());
    }
}
