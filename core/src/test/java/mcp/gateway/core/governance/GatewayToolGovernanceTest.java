package mcp.gateway.core.governance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import mcp.gateway.core.authz.ToolAuthorizationDecision;
import mcp.gateway.core.context.GatewayToolExecutionContext;
import mcp.gateway.core.invocation.McpToolInvocation;
import mcp.gateway.core.protection.McpAbuseProtectionDecision;
import org.junit.jupiter.api.Test;

class GatewayToolGovernanceTest {
    private final GatewayToolExecutionContext context = GatewayToolExecutionContext.of(
            "client-a",
            "workspace-a",
            "corr-a",
            McpToolInvocation.fromJsonRpc("tools/call", "demo_tool"),
            null
    );

    @Test
    void allowsWhenAuthorizationAndProtectionAllow() {
        GatewayToolGovernanceDecision decision = GatewayToolGovernance.evaluate(
                context,
                List.of("demo:run"),
                authorization(GatewayToolAuthorizationPolicy.enforce(), authAllowed()),
                protection(McpAbuseProtectionDecision.allow("demo_tool", "client-a", "workspace-a"))
        );

        assertTrue(decision.allowed());
        assertEquals(GatewayToolGovernanceOutcome.ALLOW, decision.outcome());
        assertEquals(GatewayToolGovernanceReason.GOVERNANCE_PASSED, decision.reason());
        assertEquals(GatewayToolGovernanceOutcome.ALLOW, decision.authorizationObservationOutcome());
        assertEquals(GatewayToolGovernanceReason.SCOPE_GRANTED, decision.authorizationObservationReason());
        assertTrue(decision.protectionDecision().allowed());
    }

    @Test
    void rejectsUnmappedAuthorizationBeforeProtectionRunsInEnforceMode() {
        AtomicBoolean protectionCalled = new AtomicBoolean(false);

        GatewayToolGovernanceDecision decision = GatewayToolGovernance.evaluate(
                context,
                List.of("demo:run"),
                authorization(GatewayToolAuthorizationPolicy.enforce(), authUnmapped()),
                new GatewayToolProtectionEvaluator() {
                    @Override
                    public boolean enabled() {
                        return true;
                    }

                    @Override
                    public McpAbuseProtectionDecision evaluate(GatewayToolExecutionContext context) {
                        protectionCalled.set(true);
                        return McpAbuseProtectionDecision.allow("demo_tool", "client-a", "workspace-a");
                    }
                }
        );

        assertFalse(decision.allowed());
        assertEquals(GatewayToolGovernanceOutcome.REJECT, decision.outcome());
        assertEquals(GatewayToolGovernanceReason.UNMAPPED_TOOL, decision.reason());
        assertEquals(GatewayToolGovernanceOutcome.REJECT, decision.authorizationObservationOutcome());
        assertEquals(GatewayToolGovernanceReason.UNMAPPED_TOOL, decision.authorizationObservationReason());
        assertNull(decision.protectionDecision());
        assertFalse(protectionCalled.get());
    }

    @Test
    void warnAuthorizationContinuesThroughProtection() {
        GatewayToolGovernanceDecision decision = GatewayToolGovernance.evaluate(
                context,
                List.of("demo:read"),
                authorization(GatewayToolAuthorizationPolicy.warn(), authDenied()),
                protection(McpAbuseProtectionDecision.allow("demo_tool", "client-a", "workspace-a"))
        );

        assertTrue(decision.allowed());
        assertEquals(GatewayToolGovernanceOutcome.WARN, decision.outcome());
        assertEquals(GatewayToolGovernanceReason.INSUFFICIENT_SCOPE, decision.reason());
        assertEquals(GatewayToolGovernanceOutcome.WARN, decision.authorizationObservationOutcome());
        assertEquals(GatewayToolGovernanceReason.INSUFFICIENT_SCOPE, decision.authorizationObservationReason());
        assertTrue(decision.protectionDecision().allowed());
    }

    @Test
    void protectionRejectionPreservesAuthorizationObservation() {
        McpAbuseProtectionDecision rejected = McpAbuseProtectionDecision.reject(
                "rate_limited",
                "too many requests",
                "demo_tool",
                "client-a",
                "workspace-a",
                5
        );

        GatewayToolGovernanceDecision decision = GatewayToolGovernance.evaluate(
                context,
                List.of("demo:run"),
                authorization(GatewayToolAuthorizationPolicy.enforce(), authAllowed()),
                protection(rejected)
        );

        assertFalse(decision.allowed());
        assertEquals(GatewayToolGovernanceOutcome.REJECT, decision.outcome());
        assertEquals(GatewayToolGovernanceReason.PROTECTION_REJECTED, decision.reason());
        assertEquals(GatewayToolGovernanceOutcome.ALLOW, decision.authorizationObservationOutcome());
        assertEquals(GatewayToolGovernanceReason.SCOPE_GRANTED, decision.authorizationObservationReason());
        assertEquals(rejected, decision.protectionDecision());
    }

    @Test
    void rejectsNullAuthorizationPolicyBeforeAuthorizationOrProtectionRuns() {
        AtomicBoolean authorizationCalled = new AtomicBoolean(false);
        AtomicBoolean protectionCalled = new AtomicBoolean(false);

        NullPointerException failure = assertThrows(NullPointerException.class, () -> GatewayToolGovernance.evaluate(
                context,
                List.of("demo:run"),
                new GatewayToolAuthorizationEvaluator() {
                    @Override
                    public GatewayToolAuthorizationPolicy policy() {
                        return null;
                    }

                    @Override
                    public ToolAuthorizationDecision authorize(Collection<String> grantedScopes,
                                                               GatewayToolExecutionContext context) {
                        authorizationCalled.set(true);
                        return authAllowed();
                    }
                },
                new GatewayToolProtectionEvaluator() {
                    @Override
                    public boolean enabled() {
                        return true;
                    }

                    @Override
                    public McpAbuseProtectionDecision evaluate(GatewayToolExecutionContext context) {
                        protectionCalled.set(true);
                        return McpAbuseProtectionDecision.allow("demo_tool", "client-a", "workspace-a");
                    }
                }
        ));

        assertEquals("authorization evaluator policy must not be null", failure.getMessage());
        assertFalse(authorizationCalled.get());
        assertFalse(protectionCalled.get());
    }

    @Test
    void skipsAuthorizationForUnauthorizableInvocationButStillEvaluatesProtection() {
        GatewayToolExecutionContext otherContext = GatewayToolExecutionContext.of(
                "client-a",
                "workspace-a",
                "corr-a",
                McpToolInvocation.fromJsonRpc("initialize", null),
                null
        );

        GatewayToolGovernanceDecision decision = GatewayToolGovernance.evaluate(
                otherContext,
                List.of("demo:run"),
                authorization(GatewayToolAuthorizationPolicy.enforce(), authDenied()),
                protection(McpAbuseProtectionDecision.allow(null, "client-a", "workspace-a"))
        );

        assertTrue(decision.allowed());
        assertFalse(decision.hasAuthorizationObservation());
        assertTrue(decision.protectionDecision().allowed());
    }

    @Test
    void rejectsNullContextAndNullEvaluatorDecisions() {
        assertThrows(NullPointerException.class, () -> GatewayToolGovernance.evaluate(
                null,
                List.of(),
                null,
                null
        ));
        assertThrows(IllegalStateException.class, () -> GatewayToolGovernance.evaluate(
                context,
                List.of(),
                authorization(GatewayToolAuthorizationPolicy.enforce(), null),
                null
        ));
        assertThrows(IllegalStateException.class, () -> GatewayToolGovernance.evaluate(
                context,
                List.of(),
                null,
                protection(null)
        ));
    }

    private GatewayToolAuthorizationEvaluator authorization(GatewayToolAuthorizationPolicy policy,
                                                           ToolAuthorizationDecision decision) {
        return new GatewayToolAuthorizationEvaluator() {
            @Override
            public GatewayToolAuthorizationPolicy policy() {
                return policy;
            }

            @Override
            public ToolAuthorizationDecision authorize(Collection<String> grantedScopes,
                                                       GatewayToolExecutionContext context) {
                return decision;
            }
        };
    }

    private GatewayToolProtectionEvaluator protection(McpAbuseProtectionDecision decision) {
        return new GatewayToolProtectionEvaluator() {
            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public McpAbuseProtectionDecision evaluate(GatewayToolExecutionContext context) {
                return decision;
            }
        };
    }

    private ToolAuthorizationDecision authAllowed() {
        return new ToolAuthorizationDecision(
                true,
                true,
                "demo_tool",
                List.of("demo:run"),
                List.of("demo:run"),
                List.of()
        );
    }

    private ToolAuthorizationDecision authDenied() {
        return new ToolAuthorizationDecision(
                false,
                true,
                "demo_tool",
                List.of("demo:run"),
                List.of("demo:read"),
                List.of("demo:run")
        );
    }

    private ToolAuthorizationDecision authUnmapped() {
        return new ToolAuthorizationDecision(
                false,
                false,
                "demo_tool",
                List.of(),
                List.of("demo:run"),
                List.of()
        );
    }
}
