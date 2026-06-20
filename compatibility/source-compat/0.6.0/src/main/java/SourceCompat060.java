import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import mcp.gateway.core.audit.GatewayAuditEmitter;
import mcp.gateway.core.audit.GatewayAuditEvent;
import mcp.gateway.core.audit.GatewayAuditSink;
import mcp.gateway.core.authz.McpToolAccessRegistry;
import mcp.gateway.core.authz.McpToolAccessRule;
import mcp.gateway.core.authz.McpToolAuthorizer;
import mcp.gateway.core.authz.ToolAuthorizationDecision;
import mcp.gateway.core.authz.ToolAuthorizationPipeline;
import mcp.gateway.core.authz.ToolAuthorizationRequest;
import mcp.gateway.core.authz.ToolAuthorizationRequirement;
import mcp.gateway.core.context.GatewayExecutionContext;
import mcp.gateway.core.context.GatewayPrincipal;
import mcp.gateway.core.context.GatewayToolExecutionContext;
import mcp.gateway.core.context.GatewayWorkspace;
import mcp.gateway.core.governance.GatewayToolAuthorizationEvaluator;
import mcp.gateway.core.governance.GatewayToolAuthorizationPolicy;
import mcp.gateway.core.governance.GatewayToolGovernance;
import mcp.gateway.core.governance.GatewayToolGovernanceDecision;
import mcp.gateway.core.governance.GatewayToolGovernanceOutcome;
import mcp.gateway.core.governance.GatewayToolProtectionEvaluator;
import mcp.gateway.core.invocation.McpToolInvocation;
import mcp.gateway.core.invocation.McpToolInvocationKind;
import mcp.gateway.core.logging.CorrelationIds;
import mcp.gateway.core.policy.ToolPolicyDecision;
import mcp.gateway.core.policy.ToolPolicyDeniedException;
import mcp.gateway.core.policy.ToolPolicyEvaluationContext;
import mcp.gateway.core.policy.ToolPolicyOutcome;
import mcp.gateway.core.policybundle.PolicyBundleDecision;
import mcp.gateway.core.policybundle.PolicyBundleDecisionSource;
import mcp.gateway.core.policybundle.PolicyBundleEvaluationRequest;
import mcp.gateway.core.policybundle.PolicyBundleEvaluationResult;
import mcp.gateway.core.policybundle.PolicyBundleEvaluator;
import mcp.gateway.core.policybundle.PolicyBundleMatch;
import mcp.gateway.core.policybundle.PolicyBundleRule;
import mcp.gateway.core.policybundle.PolicyBundleRuleset;
import mcp.gateway.core.policybundle.PolicyBundleTimeWindow;
import mcp.gateway.core.protection.McpAbuseProtectionContext;
import mcp.gateway.core.protection.McpAbuseProtectionDecision;
import mcp.gateway.core.protection.McpQuotaLimit;
import mcp.gateway.core.rate.TokenBucketRateLimiter;
import mcp.gateway.core.tool.McpToolCapability;
import mcp.gateway.core.tool.McpToolDescriptor;
import mcp.gateway.core.tool.McpToolRegistry;
import mcp.gateway.core.tool.McpToolSurface;
import mcp.gateway.core.url.UrlScope;
import mcp.gateway.spring.webflux.McpAuthorizationObservation;
import mcp.gateway.spring.webflux.McpAuthorizationObserver;
import mcp.gateway.spring.webflux.McpGatewayAbuseProtectionEvaluator;
import mcp.gateway.spring.webflux.McpGatewayAuthorizationEvaluator;
import mcp.gateway.spring.webflux.McpGatewayAuthorizationMode;
import mcp.gateway.spring.webflux.McpGatewayCorrelationIdResolver;
import mcp.gateway.spring.webflux.McpGatewayWebFluxContextResolver;
import mcp.gateway.spring.webflux.McpGatewayWebFluxGovernanceFilter;
import mcp.gateway.spring.webflux.McpGatewayWebFluxProperties;
import mcp.gateway.spring.webflux.McpGrantedScopesExtractor;
import mcp.gateway.spring.webflux.McpJsonRpcToolInvocationParser;
import mcp.gateway.spring.webflux.McpProtectionRejectionObserver;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

public final class SourceCompat060 {
    public static void main(String[] args) {
        McpToolCapability mutating = McpToolCapability.of("mutating");
        McpToolDescriptor descriptor = McpToolDescriptor.builder("demo_tool", McpToolSurface.GUIDED)
                .capability(mutating)
                .capability("report")
                .build();
        McpToolRegistry toolRegistry = McpToolRegistry.of(List.of(descriptor));
        require(toolRegistry.contains("demo_tool"), "tool registry should contain descriptor");
        require(toolRegistry.hasCapability("demo_tool", mutating), "descriptor capability should be visible");
        require(toolRegistry.descriptorsForSurface(McpToolSurface.GUIDED).size() == 1, "surface lookup should work");

        McpToolAccessRule accessRule = McpToolAccessRule.builder("demo_tool", McpToolSurface.GUIDED)
                .requiredScope("demo:run")
                .capability(mutating)
                .build();
        McpToolAccessRegistry accessRegistry = McpToolAccessRegistry.of(List.of(accessRule));
        ToolAuthorizationRequirement requirement = ToolAuthorizationRequirement.of("demo_tool", List.of("demo:run"));
        ToolAuthorizationRequest authorizationRequest = ToolAuthorizationRequest.of("demo_tool", List.of("DEMO:RUN"), false);
        ToolAuthorizationDecision pipelineDecision = ToolAuthorizationPipeline.evaluate(authorizationRequest, requirement);
        require(pipelineDecision.allowed(), "pipeline should allow normalized scope");
        require(accessRegistry.requiredScopes("demo_tool").orElseThrow().contains("demo:run"), "access registry should expose scopes");
        require(accessRegistry.toolRegistry().contains("demo_tool"), "access registry should expose descriptor registry");

        McpToolAuthorizer authorizer = McpToolAuthorizer.of(accessRegistry, List.of(McpToolAuthorizer.TOOLS_LIST_ACTION));
        ToolAuthorizationDecision toolDecision = authorizer.authorizeToolCall("demo_tool", List.of("demo:run"), false, true);
        require(toolDecision.allowed(), "tool authorizer should allow demo tool");
        require(authorizer.authorizeToolsList(List.of(McpToolAuthorizer.TOOLS_LIST_ACTION), false, true).allowed(),
                "tools/list should be authorizable");

        McpToolInvocation invocation = McpToolInvocation.fromJsonRpc(McpToolInvocation.METHOD_TOOLS_CALL, "demo_tool");
        require(invocation.kind() == McpToolInvocationKind.TOOL_CALL, "invocation should be a tool call");
        GatewayPrincipal principal = GatewayPrincipal.of("client-a");
        GatewayWorkspace workspace = GatewayWorkspace.of("workspace-a");
        GatewayExecutionContext executionContext = new GatewayExecutionContext(principal, workspace, "corr-a");
        GatewayToolExecutionContext toolContext = GatewayToolExecutionContext.of(executionContext, invocation, "https://example.com/app");
        require("demo_tool".equals(toolContext.actionName()), "tool action should be demo_tool");

        GatewayToolAuthorizationEvaluator authorizationEvaluator = new GatewayToolAuthorizationEvaluator() {
            @Override
            public GatewayToolAuthorizationPolicy policy() {
                return GatewayToolAuthorizationPolicy.enforce();
            }

            @Override
            public ToolAuthorizationDecision authorize(java.util.Collection<String> grantedScopes,
                                                       GatewayToolExecutionContext context) {
                return authorizer.authorize(context, grantedScopes, false, true);
            }
        };
        GatewayToolProtectionEvaluator protectionEvaluator = new GatewayToolProtectionEvaluator() {
            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public McpAbuseProtectionDecision evaluate(GatewayToolExecutionContext context) {
                return McpAbuseProtectionDecision.allow(context.toolName(), context.principalId(), context.workspaceId());
            }
        };
        GatewayToolGovernanceDecision governanceDecision = GatewayToolGovernance.evaluate(
                toolContext,
                List.of("demo:run"),
                authorizationEvaluator,
                protectionEvaluator
        );
        require(governanceDecision.outcome() == GatewayToolGovernanceOutcome.ALLOW, "governance should allow");

        ToolPolicyEvaluationContext policyContext = ToolPolicyEvaluationContext.from(toolContext);
        ToolPolicyDecision policyDecision = ToolPolicyDecision.allow("allowed", Map.of("tool", policyContext.toolName()));
        require(policyDecision.outcome() == ToolPolicyOutcome.ALLOW, "policy should allow");
        require(new ToolPolicyDeniedException("denied").getMessage().equals("denied"), "exception constructor should remain");

        PolicyBundleTimeWindow window = PolicyBundleTimeWindow.of(
                List.of(DayOfWeek.MONDAY),
                LocalTime.parse("09:00"),
                LocalTime.parse("17:00")
        );
        PolicyBundleRule rule = new PolicyBundleRule(
                "allow-demo",
                PolicyBundleDecision.ALLOW,
                "allow demo",
                true,
                PolicyBundleMatch.of(List.of("demo_tool"), List.of("*.example.com"), List.of(window))
        );
        PolicyBundleEvaluationResult bundleResult = PolicyBundleEvaluator.evaluate(
                PolicyBundleRuleset.of(PolicyBundleDecision.DENY, List.of(rule)),
                new PolicyBundleEvaluationRequest(
                        "demo_tool",
                        "app.example.com",
                        ZonedDateTime.of(2026, 6, 1, 10, 0, 0, 0, ZoneOffset.UTC)
                )
        );
        require(bundleResult.source() == PolicyBundleDecisionSource.RULE, "policy bundle should match rule");
        require("allow".equals(PolicyBundleDecision.fromWireValue("allow").wireValue()), "wire values should round trip");

        McpAbuseProtectionContext protectionContext = McpAbuseProtectionContext.from(toolContext);
        McpQuotaLimit quota = McpQuotaLimit.of("quota_exceeded", "quota reached", 2, 1, 30);
        require(quota.evaluate(protectionContext).allowed(), "quota should allow below limit");
        require(!McpAbuseProtectionDecision.reject("rate_limited", "slow down", protectionContext, 5).allowed(),
                "rejection helper should reject");

        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter();
        TokenBucketRateLimiter.Policy ratePolicy = new TokenBucketRateLimiter.Policy(true, 2, 1, 60, 100, 1);
        require(limiter.tryConsume("client-a:demo_tool", ratePolicy), "limiter should consume");
        require(limiter.retryAfterSeconds("client-a:demo_tool", ratePolicy) >= 1, "retry-after should be bounded");

        List<GatewayAuditEvent> auditEvents = new ArrayList<>();
        GatewayAuditSink sink = auditEvents::add;
        GatewayAuditEmitter.of(sink).emit("tool_call", "client-a", "allowed", Map.of("tool", "demo_tool"));
        GatewayAuditSink.noop().publish(GatewayAuditEvent.of("noop", "client-a", "ignored", Map.of()));
        require(auditEvents.size() == 1, "audit emitter should publish one event");

        require(CorrelationIds.resolve(" corr-a ", "legacy").equals("corr-a"), "correlation resolver should prefer primary");
        require(UrlScope.parse("https://example.com/app/").contains("https://example.com/app/page"), "url scope should contain child");

        ObjectMapper objectMapper = new ObjectMapper();
        McpJsonRpcToolInvocationParser parser = new McpJsonRpcToolInvocationParser(objectMapper);
        McpToolInvocation parsedInvocation = parser.parse(("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"demo_tool"}}
                """).getBytes(StandardCharsets.UTF_8));
        require(parsedInvocation.kind() == McpToolInvocationKind.TOOL_CALL, "adapter parser should parse tool call");

        Authentication authentication = new UsernamePasswordAuthenticationToken(
                "client-a",
                "n/a",
                List.of(new SimpleGrantedAuthority("SCOPE_DEMO:RUN"))
        );
        List<String> scopes = McpGrantedScopesExtractor.springSecurityScopes().extract(authentication);
        require(scopes.contains("demo:run"), "scope extractor should normalize scopes");

        McpGatewayAuthorizationEvaluator webFluxAuthorization = new McpGatewayAuthorizationEvaluator() {
            @Override
            public McpGatewayAuthorizationMode mode() {
                return McpGatewayAuthorizationMode.ENFORCE;
            }

            @Override
            public ToolAuthorizationDecision authorize(java.util.Collection<String> grantedScopes,
                                                       GatewayToolExecutionContext context) {
                return authorizer.authorize(context, grantedScopes, false, true);
            }
        };
        require(webFluxAuthorization.enabled(), "mode-backed authorization should be enabled");
        require(webFluxAuthorization.policy().enabled(), "mode-backed policy should be enabled");

        McpGatewayAbuseProtectionEvaluator webFluxProtection = new McpGatewayAbuseProtectionEvaluator() {
            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public McpAbuseProtectionDecision evaluate(GatewayToolExecutionContext context) {
                return McpAbuseProtectionDecision.allow(context.toolName(), context.principalId(), context.workspaceId());
            }
        };
        McpGatewayWebFluxContextResolver contextResolver = (auth, exchange, parsed) -> GatewayToolExecutionContext.of(
                auth == null ? null : auth.getName(),
                "workspace-a",
                McpGatewayCorrelationIdResolver.defaultResolver().resolve(exchange),
                parsed,
                null
        );
        McpGatewayWebFluxProperties properties = new McpGatewayWebFluxProperties("/mcp", 4096, 7);
        McpGatewayWebFluxGovernanceFilter defaultFilter = new McpGatewayWebFluxGovernanceFilter(
                objectMapper,
                properties,
                webFluxAuthorization,
                webFluxProtection,
                contextResolver
        );
        require(defaultFilter.getOrder() == 7, "filter order should come from properties");

        McpAuthorizationObserver observer = McpAuthorizationObserver.noop();
        observer.record(new McpAuthorizationObservation(
                "demo_tool",
                "allowed",
                "scope_granted",
                List.of("demo:run"),
                List.of("demo:run"),
                toolContext
        ));
        McpProtectionRejectionObserver.noop().rejected(
                McpAbuseProtectionDecision.reject("rate_limited", "slow down", protectionContext, 5),
                toolContext
        );
        new McpGatewayWebFluxGovernanceFilter(
                objectMapper,
                properties,
                webFluxAuthorization,
                webFluxProtection,
                contextResolver,
                McpGrantedScopesExtractor.springSecurityScopes(),
                observer,
                McpProtectionRejectionObserver.noop(),
                McpGatewayCorrelationIdResolver.fromHeader("X-Correlation-Id")
        );
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
