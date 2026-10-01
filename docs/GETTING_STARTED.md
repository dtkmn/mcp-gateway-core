# Getting Started

This guide shows how to use `mcp-gateway-core` inside an existing Java MCP
server. The library does not run a gateway for you. It gives your runtime common
contracts for tool identity, authorization, policy, audit, abuse protection, URL
scope checks, correlation IDs, and rate limiting.

Use the optional Spring WebFlux adapter only when your MCP HTTP endpoint runs on
Spring WebFlux. Other runtimes can use the core artifact directly and wire their
own transport adapter.

## Choose The Artifact

The main examples below target the published `0.10.0` public-preview release.
The separately marked unreleased sections describe `0.11.0-SNAPSHOT`
development APIs and behavior that are not part of published `0.10.0`.
Consumers that remain on `0.7.2` must also keep its Jackson 2 `ObjectMapper`
wiring.

Use core only when you have a non-Spring runtime, a custom transport, Quarkus,
Micronaut, servlet MVC, or another framework:

```groovy
implementation "io.github.dtkmn:mcp-gateway-core:0.10.0"
```

Use both artifacts when your MCP endpoint is a Spring WebFlux route:

```groovy
implementation "io.github.dtkmn:mcp-gateway-core:0.10.0"
implementation "io.github.dtkmn:mcp-gateway-spring-webflux:0.10.0"
```

The adapter currently targets Spring Framework 7, Spring Security 7, and
Jackson 3. Its optional integration stack uses Spring AI 2.0 and MCP Java SDK
2.0. Spring Boot 4.1 applications can inject Boot's Jackson 3 `JsonMapper`. If
your application is on Spring Boot 3 / Spring Framework 6 or still exposes a
Jackson 2 `ObjectMapper`, use the framework-neutral core artifact or remain on
the `0.7.2` adapter while completing the migration.

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

### Unreleased Strict Authorization Shortcut

In the unreleased `0.11.0-SNAPSHOT` development version, the same decision can
be calculated with a two-argument overload:

```java
ToolAuthorizationDecision decision = authorizer.authorize(List.of("files:read"), context);
```

This is equivalent to `authorize(context, grantedScopes, false, true)`:
mapped scope requirements are checked, a granted `*` does not bypass them, and
unmapped actions return a denied, unmapped decision. The method returns a
decision; your runtime or governance layer still decides whether to execute the
tool. Existing explicit overloads remain available for applications that
deliberately configure wildcard grants or dynamic decision-calculation policy.

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

String key = context.principalId();
TokenBucketRateLimiter.Attempt attempt = limiter.attempt(key, policy);
boolean allowed = attempt.allowed();
long retryAfterSeconds = attempt.retryAfterSeconds();
```

The `attempt` API is available in published `0.10.0`. Its decision and retry
delay come from the same consumption attempt: allowed requests report zero,
and rejected requests report at least one second. Use that result's retry delay
when constructing a rejection response.

This example shares one request quota across a caller's MCP actions. Derive the
principal from trusted authentication; unauthenticated callers share the
anonymous bucket. Avoid keys built from arbitrary request methods, headers, or
caller-selected identifiers: varying those values would create separate quotas.
If you also need per-tool quotas, use host-validated tool identities alongside
an overall caller limit.

## Spring WebFlux Governance Filter

The Spring WebFlux adapter is deliberately not auto-configuration. You wire the
beans so your app stays in charge of authentication, tenant resolution, tool
catalogs, protection limits, and enforcement mode.

```java
import java.util.List;
import mcp.gateway.core.authz.McpToolAccessRegistry;
import mcp.gateway.core.authz.McpToolAccessRule;
import mcp.gateway.core.authz.McpToolAuthorizer;
import mcp.gateway.core.context.GatewayToolExecutionContext;
import mcp.gateway.core.protection.McpAbuseProtectionDecision;
import mcp.gateway.core.rate.TokenBucketRateLimiter;
import mcp.gateway.core.tool.McpToolSurface;
import mcp.gateway.spring.webflux.McpGatewayAuthorizationMode;
import mcp.gateway.spring.webflux.McpGatewayCorrelationIdResolver;
import mcp.gateway.spring.webflux.McpGatewayWebFluxGovernanceFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration
class McpGatewayConfiguration {

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
    McpGatewayWebFluxGovernanceFilter mcpGatewayGovernanceFilter(
            JsonMapper jsonMapper,
            McpToolAuthorizer authorizer
    ) {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter();
        TokenBucketRateLimiter.Policy policy = new TokenBucketRateLimiter.Policy(true, 60, 60, 60, 10_000, 1);

        return McpGatewayWebFluxGovernanceFilter.builder(
                        jsonMapper,
                        (authentication, exchange, invocation) -> {
                            String principalId = authentication == null
                                    ? "anonymous"
                                    : authentication.getName();
                            String workspaceId = exchange.getRequest().getHeaders()
                                    .getFirst("X-Workspace-Id");
                            String correlationId = McpGatewayCorrelationIdResolver.defaultResolver()
                                    .resolve(exchange);
                            return GatewayToolExecutionContext.of(
                                    principalId,
                                    workspaceId,
                                    correlationId,
                                    // Preserve the invocation supplied by the adapter.
                                    invocation,
                                    null
                            );
                        }
                )
                .authorization(
                        () -> McpGatewayAuthorizationMode.ENFORCE,
                        (grantedScopes, context) -> authorizer.authorize(context, grantedScopes, false, true)
                )
                .protection(
                        () -> true,
                        context -> {
                            String key = context.principalId();
                            TokenBucketRateLimiter.Attempt attempt = limiter.attempt(key, policy);
                            if (attempt.allowed()) {
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
                                    attempt.retryAfterSeconds()
                            );
                        }
                )
                .build();
    }
}
```

The default scope extractor reads Spring Security authorities named
`SCOPE_<scope>` and passes normalized scope names into the authorization
evaluator. The governance filter evaluates authorization first, then protection,
and preserves the request body for the downstream MCP runtime. Recognized
response envelopes used to answer server-initiated JSON-RPC requests pass
through to that runtime without request authorization or action-based
abuse-protection evaluation.

### Unreleased Resolver Validation

Unreleased `0.11.0-SNAPSHOT` validates trusted resolver wiring using the existing
interface; published `0.10.0` does not perform this check. Return a non-null
context preserving the supplied invocation, as the example does. Custom resolvers
that substitute another invocation must change to preserve it. Equal copied
records are accepted, and host-owned identity/workspace/correlation/target
enrichment remains supported. This checks action identity, not tool arguments.
Null or mismatched results normally receive a fixed HTTP `500` response before
authorization/protection decisions and observations or execution. An optional
adapter diagnostic can run before that response; see the
[context-resolution contract](CONTRACT_REFERENCE.md#context-resolution-unreleased).

### Unreleased Strict Shortcut In The Builder

With the unreleased `0.11.0-SNAPSHOT` core API, replace only the authorization
builder call above with:

```java
.authorization(
        () -> McpGatewayAuthorizationMode.ENFORCE,
        authorizer::authorize
)
```

Keep the explicit lambda in the published `0.10.0` example when using that
release. The shortcut calculates the strict decision; the mode supplier still
controls what governance does with it. `ENFORCE` rejects denied or unmapped
authorizable requests, `WARN` emits a warning observation and continues to
protection, and `DISABLED` skips authorization evaluation. Protection and an
active-tool registry can still reject requests independently.

Use `WARN` to observe missing scopes without rejecting on authorization; do not
set the authorizer's `authorizationEnabled` flag to `false` for that purpose,
because that makes mapped decisions allowed instead of retaining the denial.
Applications with an intentional wildcard policy can keep the explicit
overload and lambda. Switching to the shortcut would change that policy.

### Unreleased Active Tool Catalog Selection

In unreleased `0.11.0-SNAPSHOT`, select the active catalog from the access-rule
inventory with the tool names actually registered by your server:

```java
McpToolRegistry activeTools = accessRegistry.activeToolRegistry(exposedToolNames);
```

Here `exposedToolNames` is the host's `Collection<String>` of enabled tool names.
Pass `activeTools` to `.toolRegistry(activeTools)` on the existing filter builder.
The helper preserves descriptors and capabilities, includes only those names,
and reports all missing permission mappings together before returning a registry.
An empty collection creates an empty active catalog. Names are case-sensitive;
null, blank, padded, or duplicate entries are rejected rather than normalized.
This does not discover callbacks or register tools. It consolidates the host's
selection and coverage checks; the older lookup/registry methods retain their behavior.

### Unreleased Audit Observer Helper

In unreleased `0.11.0-SNAPSHOT`, connect the adapter's four observation families
to an application-owned audit sink using the existing builder settings:

```java
import mcp.gateway.core.audit.GatewayAuditSink;
import mcp.gateway.spring.webflux.McpGatewayAuditObservers;

McpGatewayAuditObservers audits = McpGatewayAuditObservers.of(auditSink);

// Add to the existing filter builder before .build():
.authorizationObserver(audits)
.protectionRejectionObserver(audits)
.invalidRequestObserver(audits)
.adapterRejectionObserver(audits)
```

`auditSink` is the host's `GatewayAuditSink`. It chooses storage and delivery;
the helper translates pre-execution observations into audit events and does not
observe tool completion. Existing observer setters still replace their respective
callbacks. To keep metrics, compose them explicitly with the audit callback:

```java
.authorizationObserver(observation -> {
    recordAuthorizationMetrics(observation); // metrics only; no audit publication
    audits.record(observation);
})
```

The shown ordering runs metrics before audit. If either callback throws, the
remaining work and downstream execution stop through the existing reactive error
path. Do not combine the helper with a callback that already publishes the same
audit event: that would duplicate records. Diagnostic callbacks supply no
identity or tool information; the helper omits unavailable fields instead of
inventing them. See the [audit schema](CONTRACT_REFERENCE.md#audit-observer-helper-unreleased).
Both helpers in these sections are unavailable in published `0.10.0`.

### Unreleased Adapter Rejection Diagnostics

In unreleased `0.11.0-SNAPSHOT`, add this optional callback before `.build()` to
observe previously silent adapter rejections. It is unavailable in published `0.10.0`:

```java
.adapterRejectionObserver((reason, serverRequestId, correlationId) ->
        System.getLogger("mcp.gateway").log(
                System.Logger.Level.WARNING,
                "{0}: requestId={1}, correlationId={2}",
                reason.code(), serverRequestId, correlationId))
```

Omitting the callback preserves the existing behavior. Explicitly installing one,
even a no-op lambda, resolves correlation before the callback and response write.
Exceptions from either step propagate reactively and can prevent the normal error
response. Existing authorization, protection, and invalid-request observers remain
separate; see the [coverage matrix](CONTRACT_REFERENCE.md#adapter-rejection-observation-unreleased).

## Unreleased Metadata Snapshots

Existing audit events and policy decisions freeze only the outer details map;
nested values remain shared. In unreleased `0.11.0-SNAPSHOT`, opt into a recursive
snapshot before calling the existing factories (not available in published `0.10.0`):

```java
import java.util.Map;
import mcp.gateway.core.audit.GatewayAuditEvent;
import mcp.gateway.core.metadata.GatewayMetadataSnapshot;
import mcp.gateway.core.policy.ToolPolicyDecision;

Map<String, Object> snapshot = GatewayMetadataSnapshot.copyOf(details);
GatewayAuditEvent event = GatewayAuditEvent.of(
        "authorization", "user-123", "allowed", snapshot);
ToolPolicyDecision decision = ToolPolicyDecision.allow("scope_granted", snapshot);
```

Here `details` is the application's `Map<String, ?>`. Keep it unchanged during
copying and use the supported containers/scalars described in the
[metadata contract](CONTRACT_REFERENCE.md#metadata-details). Unsupported values,
cycles, or value-count/depth limits are rejected. Persistence and redaction remain
application concerns; existing callers are not automatically migrated.

## Adoption Checklist

1. Map every exposed MCP tool into `McpToolAccessRegistry`.
2. Decide the required scope for `tools/list`.
3. Resolve a stable principal ID and workspace ID before gateway checks run.
4. Decide whether unknown tools fail closed, warn, or bypass in your runtime. The
   core authorizer fails closed when enforcement is enabled.
5. Authenticate the MCP endpoint independently, and require the downstream
   runtime to bind session IDs and pending response IDs to the authenticated
   principal or tenant. Reject anonymous, cross-principal, unknown-session, and
   mismatched-ID responses.
6. Add audit and metrics at your runtime boundary. Core supplies event values;
   it does not persist them.
7. Keep product-specific names and permissions in your app, not in reusable core
   contracts.
