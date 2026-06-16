# Getting Started

This guide shows how to use `mcp-gateway-core` inside an existing Java MCP
server. The library does not run a gateway for you. It gives your runtime common
contracts for tool identity, authorization, policy, audit, abuse protection, URL
scope checks, correlation IDs, and rate limiting.

Use the optional Spring WebFlux adapter only when your MCP HTTP endpoint runs on
Spring WebFlux. Other runtimes can use the core artifact directly and wire their
own transport adapter.

## Choose The Artifact

Use core only when you have a non-Spring runtime, a custom transport, Quarkus,
Micronaut, servlet MVC, or another framework:

```groovy
implementation "io.github.dtkmn:mcp-gateway-core:0.6.0"
```

Use both artifacts when your MCP endpoint is a Spring WebFlux route:

```groovy
implementation "io.github.dtkmn:mcp-gateway-core:0.6.0"
implementation "io.github.dtkmn:mcp-gateway-spring-webflux:0.6.0"
```

The adapter currently targets the Spring Framework 7 / Spring Security 7 line.
If your application is on Spring Boot 3 / Spring Framework 6, use the core
artifact directly or add a framework-specific adapter in your own runtime.

## What Your App Still Owns

Your application still owns:

- MCP server startup and tool registration
- authentication and caller identity
- scope assignment and tenant or workspace resolution
- product-specific tool names, surfaces, and required scopes
- storage, audit persistence, metrics, and tracing backends
- actual tool execution

Core owns the neutral vocabulary and decision mechanics once your app has those
inputs.

## Core-Only Authorization

Create a tool access registry from your own tool catalog, then authorize parsed
MCP invocations against the scopes granted to the caller.

```java
import java.util.List;
import mcp.gateway.core.authz.McpToolAccessRegistry;
import mcp.gateway.core.authz.McpToolAccessRule;
import mcp.gateway.core.authz.McpToolAuthorizer;
import mcp.gateway.core.authz.ToolAuthorizationDecision;
import mcp.gateway.core.context.GatewayToolExecutionContext;
import mcp.gateway.core.invocation.McpToolInvocation;
import mcp.gateway.core.tool.McpToolSurface;

McpToolAccessRegistry registry = McpToolAccessRegistry.of(List.of(
        McpToolAccessRule.of("files.read", McpToolSurface.GUIDED, List.of("files:read")),
        McpToolAccessRule.builder("files.write", McpToolSurface.EXPERT)
                .requiredScope("files:write")
                .capability("mutating")
                .build()
));

McpToolAuthorizer authorizer = McpToolAuthorizer.of(
        registry,
        List.of("mcp:tools:list")
);

McpToolInvocation invocation = McpToolInvocation.fromJsonRpc("tools/call", "files.read");
GatewayToolExecutionContext context = GatewayToolExecutionContext.of(
        "user-123",
        "workspace-a",
        "corr-123",
        invocation,
        null
);

ToolAuthorizationDecision decision = authorizer.authorize(
        context,
        List.of("files:read"),
        false,
        true
);

if (!decision.allowed()) {
    // Return your runtime's 403 or JSON-RPC error response.
}
```

For `tools/list`, use:

```java
McpToolInvocation invocation = McpToolInvocation.fromJsonRpc("tools/list", null);
```

Unmapped authorizable actions fail closed. Wildcard scope behavior is explicit
through the `wildcardAllowed` argument.

## Core-Only Rate Limiting

Core includes a small token-bucket limiter. Your runtime chooses the key shape
and the response behavior.

```java
import mcp.gateway.core.rate.TokenBucketRateLimiter;

TokenBucketRateLimiter limiter = new TokenBucketRateLimiter();
TokenBucketRateLimiter.Policy policy = new TokenBucketRateLimiter.Policy(
        true,
        60,
        60,
        60,
        10_000,
        1
);

String key = context.principalId() + ":" + context.actionName();
boolean allowed = limiter.tryConsume(key, policy);
long retryAfterSeconds = limiter.retryAfterSeconds(key, policy);
```

## Spring WebFlux Governance Filter

The Spring WebFlux adapter is deliberately not auto-configuration. You wire the
beans so your app stays in charge of authentication, tenant resolution, tool
catalogs, protection limits, and enforcement mode.

```java
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collection;
import java.util.List;
import mcp.gateway.core.authz.McpToolAccessRegistry;
import mcp.gateway.core.authz.McpToolAccessRule;
import mcp.gateway.core.authz.McpToolAuthorizer;
import mcp.gateway.core.authz.ToolAuthorizationDecision;
import mcp.gateway.core.context.GatewayToolExecutionContext;
import mcp.gateway.core.protection.McpAbuseProtectionDecision;
import mcp.gateway.core.rate.TokenBucketRateLimiter;
import mcp.gateway.core.tool.McpToolSurface;
import mcp.gateway.spring.webflux.McpGatewayAbuseProtectionEvaluator;
import mcp.gateway.spring.webflux.McpGatewayAuthorizationEvaluator;
import mcp.gateway.spring.webflux.McpGatewayAuthorizationMode;
import mcp.gateway.spring.webflux.McpGatewayWebFluxContextResolver;
import mcp.gateway.spring.webflux.McpGatewayWebFluxGovernanceFilter;
import mcp.gateway.spring.webflux.McpGatewayWebFluxProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;

@Configuration
class McpGatewayConfiguration {

    @Bean
    McpGatewayWebFluxProperties mcpGatewayWebFluxProperties() {
        return McpGatewayWebFluxProperties.defaults();
    }

    @Bean
    McpToolAccessRegistry mcpToolAccessRegistry() {
        return McpToolAccessRegistry.of(List.of(
                McpToolAccessRule.of("files.read", McpToolSurface.GUIDED, List.of("files:read"))
        ));
    }

    @Bean
    McpToolAuthorizer mcpToolAuthorizer(McpToolAccessRegistry registry) {
        return McpToolAuthorizer.of(registry, List.of("mcp:tools:list"));
    }

    @Bean
    McpGatewayWebFluxContextResolver mcpGatewayContextResolver() {
        return (authentication, exchange, invocation) -> {
            String principalId = authentication == null ? "anonymous" : authentication.getName();
            String workspaceId = exchange.getRequest().getHeaders().getFirst("X-Workspace-Id");
            String correlationId = exchange.getRequest().getHeaders().getFirst("X-Correlation-Id");
            return GatewayToolExecutionContext.of(principalId, workspaceId, correlationId, invocation, null);
        };
    }

    @Bean
    McpGatewayAuthorizationEvaluator mcpGatewayAuthorizationEvaluator(McpToolAuthorizer authorizer) {
        return new McpGatewayAuthorizationEvaluator() {
            @Override
            public McpGatewayAuthorizationMode mode() {
                return McpGatewayAuthorizationMode.ENFORCE;
            }

            @Override
            public ToolAuthorizationDecision authorize(Collection<String> grantedScopes,
                                                       GatewayToolExecutionContext context) {
                return authorizer.authorize(context, grantedScopes, false, true);
            }
        };
    }

    @Bean
    McpGatewayAbuseProtectionEvaluator mcpGatewayAbuseProtectionEvaluator() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter();
        TokenBucketRateLimiter.Policy policy = new TokenBucketRateLimiter.Policy(true, 60, 60, 60, 10_000, 1);

        return new McpGatewayAbuseProtectionEvaluator() {
            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public McpAbuseProtectionDecision evaluate(GatewayToolExecutionContext context) {
                String key = context.principalId() + ":" + context.actionName();
                if (limiter.tryConsume(key, policy)) {
                    return McpAbuseProtectionDecision.allow(
                            context.toolName(),
                            context.principalId(),
                            context.workspaceId()
                    );
                }
                return McpAbuseProtectionDecision.reject(
                        "rate_limited",
                        "Too many MCP requests",
                        context.toolName(),
                        context.principalId(),
                        context.workspaceId(),
                        limiter.retryAfterSeconds(key, policy)
                );
            }
        };
    }

    @Bean
    McpGatewayWebFluxGovernanceFilter mcpGatewayGovernanceFilter(
            ObjectMapper objectMapper,
            McpGatewayWebFluxProperties properties,
            McpGatewayAuthorizationEvaluator authorizationEvaluator,
            McpGatewayAbuseProtectionEvaluator protectionEvaluator,
            McpGatewayWebFluxContextResolver contextResolver
    ) {
        return new McpGatewayWebFluxGovernanceFilter(
                objectMapper,
                properties,
                authorizationEvaluator,
                protectionEvaluator,
                contextResolver
        );
    }
}
```

The default scope extractor reads Spring Security authorities named
`SCOPE_<scope>` and passes normalized scope names into the authorization
evaluator. The governance filter evaluates authorization first, then protection,
and preserves the request body for the downstream MCP runtime.

## Adoption Checklist

1. Map every exposed MCP tool into `McpToolAccessRegistry`.
2. Decide the required scope for `tools/list`.
3. Resolve a stable principal ID and workspace ID before gateway checks run.
4. Decide whether unknown tools fail closed, warn, or bypass in your runtime. The
   core authorizer fails closed when enforcement is enabled.
5. Add audit and metrics at your runtime boundary. Core supplies event values;
   it does not persist them.
6. Keep product-specific names and permissions in your app, not in reusable core
   contracts.
