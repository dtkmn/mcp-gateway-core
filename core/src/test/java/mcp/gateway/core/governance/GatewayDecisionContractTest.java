package mcp.gateway.core.governance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import mcp.gateway.core.authz.McpToolAccessRegistry;
import mcp.gateway.core.authz.McpToolAccessRule;
import mcp.gateway.core.authz.McpToolAuthorizer;
import mcp.gateway.core.authz.ToolAuthorizationDecision;
import mcp.gateway.core.authz.ToolAuthorizationPipeline;
import mcp.gateway.core.authz.ToolAuthorizationRequest;
import mcp.gateway.core.authz.ToolAuthorizationRequirement;
import mcp.gateway.core.context.GatewayToolExecutionContext;
import mcp.gateway.core.invocation.McpToolInvocation;
import mcp.gateway.core.protection.McpAbuseProtectionDecision;
import mcp.gateway.core.tool.McpToolSurface;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class GatewayDecisionContractTest {
    private static final String TOOL = "records_read";
    private static final String SCOPE = "records:read";
    private static final McpToolAuthorizer AUTHORIZER = McpToolAuthorizer.of(
            McpToolAccessRegistry.of(List.of(
                    McpToolAccessRule.of(TOOL, McpToolSurface.GUIDED, List.of(SCOPE)))),
            List.of("mcp:tools:list"));

    @ParameterizedTest(name = "{0}: {1}, scopes={2}")
    @MethodSource("authorizationStates")
    void publicAuthorizerAndGovernanceComposeTheDocumentedPermissionStates(
            GatewayToolAuthorizationPolicy policy, String tool, List<String> scopes,
            boolean permissionAllowed, boolean mapped,
            GatewayToolGovernanceOutcome outcome, GatewayToolGovernanceReason observationReason) {
        AtomicInteger authorizationCalls = new AtomicInteger();
        AtomicInteger protectionCalls = new AtomicInteger();
        GatewayToolGovernanceDecision decision = GatewayToolGovernance.evaluate(
                context("tools/call", tool), scopes,
                authorization(policy, authorizationCalls), protection(true, true, protectionCalls));

        assertEquals(outcome, decision.outcome());
        assertEquals(outcome != GatewayToolGovernanceOutcome.REJECT, decision.allowed());
        assertEquals(outcome == GatewayToolGovernanceOutcome.ALLOW
                ? GatewayToolGovernanceReason.GOVERNANCE_PASSED : observationReason, decision.reason());
        assertTrue(decision.hasAuthorizationObservation());
        assertEquals(outcome, decision.authorizationObservationOutcome());
        assertEquals(observationReason, decision.authorizationObservationReason());
        assertEquals(permissionAllowed, decision.authorizationDecision().allowed());
        assertEquals(mapped, decision.authorizationDecision().mapped());
        assertEquals(mapped && !permissionAllowed ? List.of(SCOPE) : List.of(),
                decision.authorizationDecision().missingScopes());
        assertEquals(1, authorizationCalls.get());
        if (outcome == GatewayToolGovernanceOutcome.REJECT) {
            assertEquals(0, protectionCalls.get());
            assertNull(decision.protectionDecision());
        } else {
            assertEquals(1, protectionCalls.get());
            assertTrue(decision.protectionDecision().allowed());
        }
    }

    @ParameterizedTest(name = "protection rejects after {0}: {1}, scopes={2}")
    @MethodSource("authorizationBeforeProtectionRejection")
    void protectionRejectionRetainsTheEarlierPermissionObservation(
            GatewayToolAuthorizationPolicy policy, String tool, List<String> scopes,
            GatewayToolGovernanceOutcome observationOutcome, GatewayToolGovernanceReason observationReason) {
        GatewayToolGovernanceDecision decision = GatewayToolGovernance.evaluate(
                context("tools/call", tool), scopes,
                authorization(policy, new AtomicInteger()), protection(true, false, new AtomicInteger()));

        assertFalse(decision.allowed());
        assertEquals(GatewayToolGovernanceOutcome.REJECT, decision.outcome());
        assertEquals(GatewayToolGovernanceReason.PROTECTION_REJECTED, decision.reason());
        assertTrue(decision.hasAuthorizationObservation());
        assertEquals(observationOutcome, decision.authorizationObservationOutcome());
        assertEquals(observationReason, decision.authorizationObservationReason());
        assertEquals(observationOutcome == GatewayToolGovernanceOutcome.ALLOW,
                decision.authorizationDecision().allowed());
        assertFalse(decision.protectionDecision().allowed());
        assertEquals(7, decision.protectionDecision().retryAfterSeconds());
    }

    @ParameterizedTest(name = "{0}, protection allowed={1}")
    @MethodSource("skippedAuthorizationStates")
    void skippedAuthorizationHasNoDecisionOrObservationEvenWhenProtectionRejects(
            AuthorizationSkip skip, boolean protectionAllowed) {
        AtomicInteger authorizationCalls = new AtomicInteger();
        AtomicInteger protectionCalls = new AtomicInteger();
        GatewayToolAuthorizationEvaluator evaluator = skip == AuthorizationSkip.ABSENT ? null
                : authorization(skip == AuthorizationSkip.DISABLED
                        ? GatewayToolAuthorizationPolicy.disabled() : GatewayToolAuthorizationPolicy.enforce(),
                        authorizationCalls);
        GatewayToolExecutionContext context = skip == AuthorizationSkip.NON_AUTHORIZABLE
                ? context("initialize", null) : context("tools/call", TOOL);

        GatewayToolGovernanceDecision decision = GatewayToolGovernance.evaluate(
                context, List.of(), evaluator, protection(true, protectionAllowed, protectionCalls));

        assertEquals(protectionAllowed, decision.allowed());
        assertEquals(protectionAllowed ? GatewayToolGovernanceOutcome.ALLOW : GatewayToolGovernanceOutcome.REJECT,
                decision.outcome());
        assertEquals(protectionAllowed ? GatewayToolGovernanceReason.GOVERNANCE_PASSED
                : GatewayToolGovernanceReason.PROTECTION_REJECTED, decision.reason());
        assertFalse(decision.hasAuthorizationObservation());
        assertNull(decision.authorizationDecision());
        assertNull(decision.authorizationObservationOutcome());
        assertNull(decision.authorizationObservationReason());
        assertEquals(protectionAllowed, decision.protectionDecision().allowed());
        assertEquals(0, authorizationCalls.get());
        assertEquals(1, protectionCalls.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void absentOrDisabledProtectionLeavesItsDecisionNull(boolean absent) {
        AtomicInteger protectionCalls = new AtomicInteger();
        GatewayToolGovernanceDecision decision = GatewayToolGovernance.evaluate(
                context("tools/call", TOOL), List.of(SCOPE),
                authorization(GatewayToolAuthorizationPolicy.enforce(), new AtomicInteger()),
                absent ? null : protection(false, false, protectionCalls));

        assertTrue(decision.allowed());
        assertTrue(decision.hasAuthorizationObservation());
        assertNull(decision.protectionDecision());
        assertEquals(0, protectionCalls.get());
    }

    @Test
    void missingScopesReflectWildcardAndDisabledCheckPoliciesRatherThanLiteralGrants() {
        ToolAuthorizationRequirement requirement = ToolAuthorizationRequirement.of(TOOL, List.of(SCOPE));
        ToolAuthorizationDecision wildcard = ToolAuthorizationPipeline.evaluate(
                ToolAuthorizationRequest.of(TOOL, List.of("*"), true), requirement);
        ToolAuthorizationDecision disabled = AUTHORIZER.authorize(
                context("tools/call", TOOL), List.of(), false, false);

        for (ToolAuthorizationDecision decision : List.of(wildcard, disabled)) {
            assertTrue(decision.allowed());
            assertTrue(decision.mapped());
            assertEquals(List.of(SCOPE), decision.requiredScopes());
            assertFalse(decision.grantedScopes().contains(SCOPE));
            assertEquals(List.of(), decision.missingScopes());
        }
        assertEquals(List.of("*"), wildcard.grantedScopes());
        assertEquals(List.of(), disabled.grantedScopes());
        assertFalse(ToolAuthorizationPipeline.evaluate(
                ToolAuthorizationRequest.of(TOOL, List.of(), false), requirement).allowed());
    }

    @Test
    void directAuthorizationConstructionCopiesListsButDoesNotValidateTheirMeaning() {
        ToolAuthorizationDecision decision = new ToolAuthorizationDecision(
                true, false, " records_read ", List.of(" RAW ", " RAW "), null, List.of(SCOPE));

        assertEquals(TOOL, decision.actionName());
        assertTrue(decision.allowed());
        assertFalse(decision.mapped());
        assertEquals(List.of(" RAW ", " RAW "), decision.requiredScopes());
        assertEquals(List.of(), decision.grantedScopes());
        assertEquals(List.of(SCOPE), decision.missingScopes());
        assertThrows(UnsupportedOperationException.class, () -> decision.requiredScopes().clear());
        assertThrows(IllegalArgumentException.class,
                () -> new ToolAuthorizationDecision(true, true, " ", null, null, null));
        assertThrows(NullPointerException.class, () -> new ToolAuthorizationDecision(
                true, true, TOOL, java.util.Arrays.asList((String) null), null, null));
    }

    @Test
    void directAggregateConstructionRetainsContradictoryNestedDecisionsForCompatibility() {
        McpAbuseProtectionDecision protection = McpAbuseProtectionDecision.reject(
                "quota", "busy", TOOL, "client", "workspace", 7);
        GatewayToolGovernanceDecision decision = new GatewayToolGovernanceDecision(
                GatewayToolGovernanceOutcome.ALLOW, GatewayToolGovernanceReason.PROTECTION_REJECTED,
                null, null, null, protection);

        assertTrue(decision.allowed());
        assertFalse(decision.hasAuthorizationObservation());
        assertSame(protection, decision.protectionDecision());
        assertFalse(decision.protectionDecision().allowed());
    }

    @Test
    void directAggregateObservationPredicateDoesNotValidateACompleteObservation() {
        ToolAuthorizationDecision authorization = AUTHORIZER.authorize(List.of(), context("tools/call", TOOL));
        GatewayToolGovernanceDecision partial = new GatewayToolGovernanceDecision(
                GatewayToolGovernanceOutcome.WARN, GatewayToolGovernanceReason.INSUFFICIENT_SCOPE,
                authorization, GatewayToolGovernanceOutcome.WARN, null, null);

        assertTrue(partial.allowed());
        assertFalse(partial.authorizationDecision().allowed());
        assertTrue(partial.hasAuthorizationObservation());
        assertNull(partial.authorizationObservationReason());
        assertThrows(NullPointerException.class, () -> new GatewayToolGovernanceDecision(
                null, GatewayToolGovernanceReason.GOVERNANCE_PASSED, null, null, null, null));
        assertThrows(NullPointerException.class, () -> new GatewayToolGovernanceDecision(
                GatewayToolGovernanceOutcome.ALLOW, null, null, null, null, null));
    }

    private static Stream<Arguments> authorizationStates() {
        return Stream.of(
                Arguments.of(GatewayToolAuthorizationPolicy.enforce(), TOOL, List.of(SCOPE), true, true,
                        GatewayToolGovernanceOutcome.ALLOW, GatewayToolGovernanceReason.SCOPE_GRANTED),
                Arguments.of(GatewayToolAuthorizationPolicy.enforce(), TOOL, List.of(), false, true,
                        GatewayToolGovernanceOutcome.REJECT, GatewayToolGovernanceReason.INSUFFICIENT_SCOPE),
                Arguments.of(GatewayToolAuthorizationPolicy.enforce(), "unmapped", List.of(SCOPE), false, false,
                        GatewayToolGovernanceOutcome.REJECT, GatewayToolGovernanceReason.UNMAPPED_TOOL),
                Arguments.of(GatewayToolAuthorizationPolicy.warn(), TOOL, List.of(SCOPE), true, true,
                        GatewayToolGovernanceOutcome.ALLOW, GatewayToolGovernanceReason.SCOPE_GRANTED),
                Arguments.of(GatewayToolAuthorizationPolicy.warn(), TOOL, List.of(), false, true,
                        GatewayToolGovernanceOutcome.WARN, GatewayToolGovernanceReason.INSUFFICIENT_SCOPE),
                Arguments.of(GatewayToolAuthorizationPolicy.warn(), "unmapped", List.of(SCOPE), false, false,
                        GatewayToolGovernanceOutcome.WARN, GatewayToolGovernanceReason.UNMAPPED_TOOL));
    }

    private static Stream<Arguments> authorizationBeforeProtectionRejection() {
        return Stream.of(
                Arguments.of(GatewayToolAuthorizationPolicy.enforce(), TOOL, List.of(SCOPE),
                        GatewayToolGovernanceOutcome.ALLOW, GatewayToolGovernanceReason.SCOPE_GRANTED),
                Arguments.of(GatewayToolAuthorizationPolicy.warn(), TOOL, List.of(SCOPE),
                        GatewayToolGovernanceOutcome.ALLOW, GatewayToolGovernanceReason.SCOPE_GRANTED),
                Arguments.of(GatewayToolAuthorizationPolicy.warn(), TOOL, List.of(),
                        GatewayToolGovernanceOutcome.WARN, GatewayToolGovernanceReason.INSUFFICIENT_SCOPE),
                Arguments.of(GatewayToolAuthorizationPolicy.warn(), "unmapped", List.of(SCOPE),
                        GatewayToolGovernanceOutcome.WARN, GatewayToolGovernanceReason.UNMAPPED_TOOL));
    }

    private static Stream<Arguments> skippedAuthorizationStates() {
        return Stream.of(AuthorizationSkip.values()).flatMap(skip -> Stream.of(true, false)
                .map(allowed -> Arguments.of(skip, allowed)));
    }

    private enum AuthorizationSkip { ABSENT, DISABLED, NON_AUTHORIZABLE }

    private static GatewayToolExecutionContext context(String method, String tool) {
        return GatewayToolExecutionContext.of("client", "workspace", "correlation",
                McpToolInvocation.fromJsonRpc(method, tool), null);
    }

    private static GatewayToolAuthorizationEvaluator authorization(
            GatewayToolAuthorizationPolicy policy, AtomicInteger calls) {
        return new GatewayToolAuthorizationEvaluator() {
            @Override
            public GatewayToolAuthorizationPolicy policy() {
                return policy;
            }

            @Override
            public ToolAuthorizationDecision authorize(Collection<String> scopes, GatewayToolExecutionContext context) {
                calls.incrementAndGet();
                return AUTHORIZER.authorize(scopes, context);
            }
        };
    }

    private static GatewayToolProtectionEvaluator protection(boolean enabled, boolean allowed, AtomicInteger calls) {
        return new GatewayToolProtectionEvaluator() {
            @Override
            public boolean enabled() {
                return enabled;
            }

            @Override
            public McpAbuseProtectionDecision evaluate(GatewayToolExecutionContext context) {
                calls.incrementAndGet();
                return allowed ? McpAbuseProtectionDecision.allow(context.toolName(), context.principalId(), context.workspaceId())
                        : McpAbuseProtectionDecision.reject("quota", "busy", context.toolName(),
                                context.principalId(), context.workspaceId(), 7);
            }
        };
    }
}
