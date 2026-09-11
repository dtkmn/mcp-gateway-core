# Contract Reference

This reference explains the vocabulary used by `mcp-gateway-core` and the
optional Spring WebFlux adapter. It is written for engineers wiring the library
into an MCP server, not for people reading the source tree.

The core artifact is intentionally MCP-neutral. It does not know your product,
database, authentication provider, tenant model, or tool execution code. Your
runtime supplies those values, and core normalizes/evaluates them consistently.

## Shared Conventions

- Blank string values usually normalize to `null` when the value is optional.
- Required identifiers throw `IllegalArgumentException` when blank.
- Scope collections normalize to lower case and are de-duplicated in insertion
  order. Configured required scopes must also be valid RFC 6749 scope tokens.
- Unmapped authorizable actions fail closed when authorization is enabled.
- Product-specific terms such as scanner names, internal roles, or tenant
  routing rules belong in the consuming runtime, not in core contracts.

## Invocation

Package: `mcp.gateway.core.invocation`

`McpToolInvocation` is the normalized MCP JSON-RPC action visible to gateway
controls.

| Field | Meaning | Runtime Responsibility |
| --- | --- | --- |
| `kind` | Classification of the JSON-RPC request. | Parse the incoming request and pass the right method/tool name. |
| `method` | JSON-RPC method such as `tools/list` or `tools/call`. | Preserve the method from the MCP request. |
| `toolName` | MCP tool name for `tools/call`. | Extract from `params.name` or the equivalent server SDK shape. |

Invocation kinds:

| Value | Meaning |
| --- | --- |
| `TOOL_CALL` | A `tools/call` invocation with a concrete tool name. |
| `TOOLS_LIST` | A `tools/list` invocation. |
| `OTHER` | A known JSON-RPC method outside the gateway-controlled tool surface. |
| `UNKNOWN` | A malformed or unavailable invocation. |

`authorizable()` is true only for `TOOL_CALL` and `TOOLS_LIST`.
`actionName()` returns the tool name for tool calls and the JSON-RPC method for
other known invocations.

## Execution Context

Package: `mcp.gateway.core.context`

`GatewayExecutionContext` represents request identity shared by gateway
controls.

| Field | Meaning | Runtime Responsibility |
| --- | --- | --- |
| `principal` | Caller identity known to the runtime. | Map from your auth provider, API key, JWT subject, service account, or anonymous fallback. |
| `workspace` | Tenant, workspace, or isolation selector. | Map from your tenancy model. Use the default only when the runtime has no isolation boundary. |
| `correlationId` | Request correlation identifier. | Resolve from headers, request IDs, trace IDs, or another runtime source. |

Fallbacks:

| Value | Meaning |
| --- | --- |
| `GatewayPrincipal.ANONYMOUS_ID` | Conventional caller id for unauthenticated or unknown callers. |
| `GatewayWorkspace.DEFAULT_ID` | Conventional workspace id for single-tenant runtimes. |

`GatewayToolExecutionContext` joins the execution context with an MCP
invocation.

| Field | Meaning |
| --- | --- |
| `executionContext` | Principal, workspace, and correlation metadata. |
| `invocation` | Normalized MCP invocation. |
| `target` | Optional domain target such as a URL, host, dataset, environment, or resource selector. |

`target` is runtime-defined. Core treats it as a neutral selector used by policy
or audit logic; it does not infer scanner or network meaning.

## Tool Catalog

Packages: `mcp.gateway.core.tool`, `mcp.gateway.core.authz`

`McpToolDescriptor` describes one MCP tool.

| Field | Meaning |
| --- | --- |
| `name` | Exact MCP tool name exposed by the server. |
| `surface` | Logical surface that owns the tool, such as guided or expert. |
| `capabilities` | Low-cardinality labels for behavior classes such as read-only, mutating, reporting, or long-running. |

`McpToolSurface` has two conventional values:

| Value | Meaning |
| --- | --- |
| `guided` | A safer/default/user-guided tool surface. |
| `expert` | A broader or lower-level tool surface. |

You may define custom surfaces with `McpToolSurface.of(...)`. Keep them stable
and product-neutral enough that downstream policy and UI can reason about them.

`McpToolCapability` is a normalized label attached to a tool. Core does not
assign semantics to capability names; your runtime does. Good capability labels
are stable and low-cardinality, for example `read-only`, `mutating`, `report`,
or `long-running`.

## Authorization

Package: `mcp.gateway.core.authz`

`McpToolAccessRule` joins tool metadata with required scopes.

| Field | Meaning |
| --- | --- |
| `toolName` | Exact MCP tool name. |
| `surface` | Tool surface. |
| `requiredScopes` | Non-empty RFC 6749 scope tokens required to invoke the tool. |
| `capabilities` | Optional behavior labels copied into the descriptor registry. |

`McpToolAccessRegistry` is an immutable registry of access rules. Duplicate
tool names are rejected. Empty registries are allowed, but unmapped actions fail
closed when authorization is enabled.

`ToolAuthorizationRequest` is the input to scope evaluation.

| Field | Meaning |
| --- | --- |
| `actionName` | Tool name or synthetic action such as `mcp:tools:list`. |
| `grantedScopes` | Caller scopes after normalization. |
| `wildcardAllowed` | Whether `*` grants all required scopes. |

`ToolAuthorizationRequirement` is the mapped requirement for one action. Empty
requirements and values outside the RFC 6749 `scope-token` character set are
invalid. Use a missing requirement to represent an unmapped action.

`ToolAuthorizationDecision` is the result.

| Field | Meaning |
| --- | --- |
| `allowed` | Whether the request should pass authorization. |
| `mapped` | Whether the action had a configured requirement. |
| `actionName` | Normalized action evaluated. |
| `requiredScopes` | Required scopes for the mapped action. |
| `grantedScopes` | Caller scopes considered by the evaluation. |
| `missingScopes` | Required scopes not present in `grantedScopes`. |

`McpToolAuthorizer` adds MCP-specific flow:

| Constant | Meaning |
| --- | --- |
| `mcp:tools:list` | Synthetic action used to authorize `tools/list`. |
| `unknown` | Synthetic action used for missing, malformed, or unauthorizable context. |

## Policy Decisions

Package: `mcp.gateway.core.policy`

`ToolPolicyEvaluationContext` is the neutral input for policy providers.

| Field | Meaning |
| --- | --- |
| `toolName` | MCP tool name, when available. |
| `target` | Runtime-defined target selector. |
| `correlationId` | Request correlation id. |

`ToolPolicyOutcome` values:

| Value | Meaning |
| --- | --- |
| `ALLOW` | Provider allows the tool call. |
| `DENY` | Provider denies the tool call. |
| `ABSTAIN` | Provider does not decide. |

`ToolPolicyDecision` contains:

| Field | Meaning |
| --- | --- |
| `outcome` | Allow, deny, or abstain. Null normalizes to deny. |
| `reason` | Human-readable reason. |
| `details` | Machine-readable details supplied by the provider. Null keys/values are dropped. |

The consuming runtime decides how multiple policy providers combine. A common
safe model is deny-wins, all-abstain-fails-closed.

## Policy Bundle Evaluation

Package: `mcp.gateway.core.policybundle`

Policy bundles are first-match rule sets for simple tool/host/time governance.

`PolicyBundleRuleset` contains:

| Field | Meaning |
| --- | --- |
| `defaultDecision` | Decision used when no enabled rule matches. |
| `rules` | Ordered rules, capped by `MAX_RULES` (`50`). |

`PolicyBundleRule` contains:

| Field | Meaning |
| --- | --- |
| `id` | Stable rule identifier. |
| `decision` | Rule decision, `ALLOW` or `DENY`. |
| `reason` | Human-readable reason returned in traces. |
| `enabled` | Whether the rule participates in matching. |
| `match` | Selector set. |

`PolicyBundleMatch` contains at least one selector dimension:

| Field | Meaning |
| --- | --- |
| `tools` | Exact, case-sensitive MCP tool names. |
| `hosts` | Exact or wildcard host patterns. |
| `timeWindows` | Bundle-local day/time windows. |

Blank selector entries are rejected instead of silently broadening a rule.
Host selectors normalize to lower case. A `*.example.com` selector matches
subdomains but not the `example.com` apex.

`PolicyBundleTimeWindow` contains:

| Field | Meaning |
| --- | --- |
| `days` | Days on which the window starts. |
| `start` | Inclusive local start time. |
| `end` | Exclusive local end time. |

Windows may wrap midnight when `start` is after `end`. For an overnight window,
`days` selects the start day: a Monday `23:00` to `02:00` window also matches the
following Tuesday before `02:00`. Equal start/end is invalid.

`PolicyBundleEvaluationRequest` contains:

| Field | Meaning |
| --- | --- |
| `toolName` | Exact tool/action name being evaluated. |
| `normalizedHost` | Lower-case host, or null for hostless calls. |
| `bundleTime` | Evaluation time already converted into the bundle timezone. |

`PolicyBundleEvaluationResult` contains the final decision, whether it came
from a rule or default, the matched rule id if any, and a trace of evaluated
rules.

## Audit

Package: `mcp.gateway.core.audit`

`GatewayAuditEvent` is the neutral event value.

| Field | Meaning |
| --- | --- |
| `type` | Event type, chosen by the runtime. |
| `principal` | Actor or client id. |
| `outcome` | Runtime-normalized outcome such as allowed, denied, rejected, or failed. |
| `details` | Machine-readable event data. Null keys/values are dropped. |

`GatewayAuditSink` receives non-null events. `GatewayAuditEmitter` owns fallback
normalization if callers emit null.

Core does not persist audit events. Your runtime decides whether events go to
logs, storage, metrics, traces, SIEM, or all of those.

## Abuse Protection And Quotas

Package: `mcp.gateway.core.protection`

`McpAbuseProtectionContext` contains:

| Field | Meaning |
| --- | --- |
| `toolName` | MCP tool name, when available. |
| `clientId` | Caller/client identifier used for protection keys. |
| `workspaceId` | Workspace/tenant identifier used for isolation-aware limits. |

`McpAbuseProtectionDecision` contains:

| Field | Meaning |
| --- | --- |
| `allowed` | Whether the request should pass protection checks. |
| `errorCode` | Machine-readable rejection code. |
| `reason` | Human-readable rejection reason. |
| `toolName` | Tool associated with the decision. |
| `clientId` | Caller/client associated with the decision. |
| `workspaceId` | Workspace associated with the decision. |
| `retryAfterSeconds` | Suggested retry delay for rejected requests. |

Direct construction is normalized to the same invariants as the factories:
allow decisions clear rejection code/reason and use a zero retry delay; rejected
decisions default blank code/reason to `protection_rejected` and clamp the retry
delay to at least one second. Optional tool/client/workspace identifiers are
trimmed and blank values become null.

`McpQuotaLimit` is a simple count-based quota helper. It rejects when
`currentCount >= maxAllowed`.

## Governance Orchestration

Package: `mcp.gateway.core.governance`

`GatewayToolGovernance` runs the common framework-neutral gateway decision
flow: authorization first, then abuse protection. It returns one
`GatewayToolGovernanceDecision` that tells the runtime whether to pass, warn, or
reject before tool execution.

The runtime still owns the concrete authorization and protection providers. Core
only coordinates their decisions.

| Type | Meaning |
| --- | --- |
| `GatewayToolAuthorizationPolicy` | Whether authorization is disabled, warn-only, or enforcing. |
| `GatewayToolAuthorizationEvaluator` | Runtime-supplied authorization decision provider. |
| `GatewayToolProtectionEvaluator` | Runtime-supplied protection/quota decision provider. |
| `GatewayToolGovernanceDecision` | Final pass/warn/reject result plus underlying decisions. |

Authorization rejection short-circuits protection. Authorization warn decisions
continue into protection so runtimes can observe policy drift without bypassing
rate limits or quotas. Protection rejection preserves any authorization
observation so downstream adapters can emit both facts accurately.

## Rate Limiting

Package: `mcp.gateway.core.rate`

`TokenBucketRateLimiter` is a thread-safe token bucket keyed by a runtime string.
The runtime chooses whether the key is per client, client/tool, workspace/tool,
IP, API key, or another shape.

`TokenBucketRateLimiter.Policy` contains:

| Field | Meaning |
| --- | --- |
| `enabled` | Whether limiting is active. |
| `capacity` | Maximum stored tokens. |
| `refillTokens` | Tokens added per refill period. |
| `refillPeriodSeconds` | Refill period. |
| `maxTrackedKeys` | Maximum bucket keys retained in memory. Minimum normalized value is `1`. |
| `disabledRetryAfterSeconds` | Retry delay returned when the policy is disabled. |

When the limiter is at `maxTrackedKeys` and no stale key can be evicted, new
fresh keys fail closed instead of growing memory.

Key creation and stale eviction are coordinated so concurrent callers cannot
temporarily exceed the configured cap. Stale-age arithmetic saturates instead of
overflowing for extreme refill periods. If an existing key's capacity or refill
settings change, the new policy applies from that point forward; elapsed time is
not retroactively credited at the new rate.

## URL Scope And Correlation IDs

Package: `mcp.gateway.core.url`

`UrlScope` checks whether a candidate URL stays inside an allowed base URL. It
is useful for product runtimes that need target or callback confinement. Core
does not fetch URLs.

Hosts must already use ASCII or punycode and are lower-cased before comparison;
raw Unicode hosts are rejected to avoid IDNA-version mapping differences across
clients. Default HTTP/HTTPS ports are treated as equivalent to explicit ports.
Paths are normalized and matched on segment boundaries. To avoid downstream
parser ambiguity, `UrlScope` rejects user information, encoded or literal
backslashes, control characters, percent-encoded percent signs (multiply encoded
paths), malformed percent-encoded UTF-8, malformed authorities, and invalid
ports. Invalid candidates return `false`; an invalid base URL is rejected during
`parse`.

Package: `mcp.gateway.core.logging`

`CorrelationIds` defines:

| Value | Meaning |
| --- | --- |
| `X-Correlation-Id` | Preferred request correlation header. |
| `X-Request-Id` | Legacy/fallback request id header. |

Caller-supplied correlation values are trimmed, capped at 128 characters, and
accepted only when they contain log-safe ASCII letters, digits, `.`, `_`, `:`,
`/`, or `-`. Unsafe values fall through to the next resolver source.

## Spring WebFlux Adapter

Package: `mcp.gateway.spring.webflux`

The adapter parses MCP JSON-RPC messages from a WebFlux exchange and applies
core decisions before request messages reach your MCP runtime. It is not Spring
Boot auto-configuration.

### Filter Builder

Available in `0.9.0`.

`McpGatewayWebFluxGovernanceFilter.builder(JsonMapper,
McpGatewayWebFluxContextResolver)` provides named configuration over the same
filter behavior as the public constructors unless an optional tool registry is
configured as described below.

The mapper and context resolver are required, and all builder method arguments
must be non-null. Unspecified options use these defaults:

- `McpGatewayWebFluxProperties.defaults()`;
- `McpGrantedScopesExtractor.springSecurityScopes()`;
- `McpAuthorizationObserver.noop()`;
- `McpProtectionRejectionObserver.noop()`;
- `McpGatewayCorrelationIdResolver.defaultResolver()`;
- `McpInvalidRequestObserver.noop()`; and
- no tool registry.

Authorization and protection do not have implicit evaluators. At least one
authorization evaluator, protection evaluator, or tool registry must be
supplied; otherwise `build()` throws `IllegalStateException`. This catches
accidental omission of every filtering concern. A configured dynamic mode or
enablement supplier can still disable authorization and protection at request
time. Exact inactive pass-through then applies only when no tool registry
is configured.

Optional builder methods are:

| Method | Purpose |
| --- | --- |
| `properties(McpGatewayWebFluxProperties)` | Replaces the default endpoint, body limit, and filter order. |
| `authorizationEvaluator(McpGatewayAuthorizationEvaluator)` | Supplies a complete authorization evaluator for consumers that need its full policy contract. |
| `authorization(Supplier<McpGatewayAuthorizationMode>, BiFunction<Collection<String>, GatewayToolExecutionContext, ToolAuthorizationDecision>)` | Adapts a dynamic mode supplier and an authorization callback without requiring an anonymous evaluator class. |
| `protectionEvaluator(McpGatewayAbuseProtectionEvaluator)` | Supplies a complete abuse-protection evaluator. |
| `protection(BooleanSupplier, Function<GatewayToolExecutionContext, McpAbuseProtectionDecision>)` | Adapts a dynamic enabled supplier and protection callback without requiring an anonymous evaluator class. |
| `grantedScopesExtractor(McpGrantedScopesExtractor)` | Replaces Spring Security `SCOPE_` authority extraction. |
| `authorizationObserver(McpAuthorizationObserver)` | Receives authorization observations. |
| `protectionRejectionObserver(McpProtectionRejectionObserver)` | Receives rejected protection decisions. |
| `correlationIdResolver(McpGatewayCorrelationIdResolver)` | Replaces default correlation-header resolution. |
| `invalidRequestObserver(McpInvalidRequestObserver)` | Receives invalid-request rejections without request payloads. |
| `toolRegistry(McpToolRegistry)` | Uses the existing core registry of exactly the runtime's registered, enabled tools to check availability before tool-call authorization. |

Choose either the complete evaluator method or the paired callback method for
each governance concern. The supplier forms are evaluated at request time, so
an application can retain runtime-controlled authorization modes and protection
flags. The builder does not register the result with Spring; applications still
expose the built filter through their own `@Bean` method or equivalent wiring.

<a id="active-tool-registry-unreleased"></a>

### Active-Tool Registry

Available in `0.10.0`.

The optional `toolRegistry(McpToolRegistry)` builder input reuses the existing
core registry directly. It must contain exactly the tools registered and enabled
in the hosting MCP runtime, not every tool the product might support. Membership
is an availability check, independent of the current caller's permissions.
Explicit null is rejected; an empty registry means no tool calls are available,
not that the availability check is disabled.

Build this immutable snapshot from actual registrations, and validate that every
active tool has a permission rule before accepting traffic. When an existing
`McpToolAccessRegistry` is assembled from those validated active-only rules, its
`toolRegistry()` can be reused without creating or maintaining a third tool list:

```java
McpToolAccessRegistry activeAccessRegistry =
        McpToolAccessRegistry.of(validatedActiveToolRules);

McpGatewayWebFluxGovernanceFilter filter =
        McpGatewayWebFluxGovernanceFilter.builder(jsonMapper, contextResolver)
                .authorization(modeSupplier, authorizationCallback)
                .toolRegistry(activeAccessRegistry.toolRegistry())
                .build();
```

The authorization callback can use the same access registry. The core registries
do not themselves discover registrations or enforce active-only membership; the
host owns that validation and assembly. A full product permission registry can
include disabled tools, and a registry assembled only from available permission
rules can silently omit an exposed tool whose rule is missing. Neither is a
substitute for validating against actual registrations.

`McpToolRegistry` is an immutable descriptor snapshot. The adapter checks its
case-sensitive membership using the already validated tool name; modifying the
source collection does not update it. Keep the supplied registry consistent
with runtime dispatch and discovery. The adapter does not refresh the registry,
register tools, or rewrite `tools/list` responses. Disabled tools must also be
absent from the runtime's discovery results.

The hosting application's security chain must authenticate requests before this
filter runs. For a valid tool-call request, the adapter then checks availability
before resolving tool execution context, extracting scopes, evaluating
permissions, applying abuse protection, or reaching downstream execution.
Unknown and disabled tools have the same public response: HTTP `200`, JSON-RPC
error `-32602` with message `Unknown tool`, and no `WWW-Authenticate` challenge.
The response does not disclose tool names, disabled status, or suggested tools.

The registry check remains active in authorization `DISABLED` and `WARN` modes,
including when abuse protection is disabled. An available tool with an unmapped
authorization decision is a generic JSON-RPC internal error when authorization
is enforced. This is a server configuration problem, not proof that the tool is
unknown and not a request for additional permissions. In `WARN` and `DISABLED`,
the existing authorization-mode semantics remain unchanged.

Registry-aware `tools/call` requests preserve their string or integer JSON-RPC
`id` in error responses. An explicit null, fractional number, any other value
type, or case-variant `id` field is rejected with HTTP `400`, JSON-RPC
error `-32600` (`Invalid Request`), and `id: null`. An otherwise well-shaped
`tools/call` message without an `id` is treated as a notification for transport
purposes: it receives HTTP `202` with an empty body and is not evaluated or
executed, because tool calls require a request identifier. Other valid
notifications retain their existing downstream behavior. These additional
tool-call rules apply only when the tool registry is configured; existing
constructors and configurations without a registry retain their legacy behavior.

This option does not change core tool-registry or authorization contracts, or
the framework-neutral authorization engine. The adapter has no Spring AI
dependency. Consumers such as ZAP Server must separately assemble and supply
their active registry through this option; an adapter
version upgrade alone does not enable the registry-aware behavior. This adapter
change does not perform that runtime integration.

### Request Handling

Only application-relative `POST` requests matching the configured endpoint are
governed. Context paths are excluded before comparison, and matrix parameters do
not change a segment's route value, so `/app/mcp;v=1` can match a configured
`/mcp` endpoint under context path `/app`. Extra path segments do not match.

Filtering is active when authorization policy is enabled, abuse protection is
enabled, or a tool registry is configured. When filtering is active, the
adapter rejects invalid MCP JSON-RPC message shapes before principal lookup,
context resolution, scope extraction,
authorization, protection, or downstream body replay. Rejected invalid shapes
return adapter JSON with HTTP `400`, `Content-Type: application/json`, `error`
set to `invalid_json_rpc_request`, a low-cardinality `reason`, ISO-8601
`timestamp`, resolved `correlationId`, and the server request id as `requestId`.
JSON-RPC `id` is never reflected as `requestId`.
Duplicate fields anywhere in the JSON object and case variants of
governance-significant `method`, `params`, and tool `name` fields are rejected
to avoid parser differentials. Method and `tools/call` tool-name strings must
not be blank or carry leading/trailing whitespace.

A method-absent JSON object is recognized as a JSON-RPC response envelope only
when it has a string or numeric `id` and exactly one of the `result` or `error`
members. Member presence determines the envelope, so `result: null` is still a
response. Any `method` member, including `method: null`, prevents response
classification; adding a response field to a tool request cannot bypass
governance. Recognized responses are replayed downstream byte-for-byte without
principal lookup, context resolution, scope extraction, request authorization,
action-based abuse protection, or their observers. Request headers, including
`Mcp-Session-Id`, remain available downstream. The surrounding security filter
chain, the adapter body-size limit, and downstream protocol/session validation
still apply. The downstream MCP runtime owns response correlation and the final
HTTP status.

The adapter does not require or validate the JSON-RPC `jsonrpc` version field
for requests or recognized responses and is not a complete protocol validator.
For messages that reach the downstream runtime, that runtime remains
responsible for protocol validation.

Batch arrays are unsupported by the governance adapter only while filtering is
active. They return `400` with reason `batch_not_supported` in that mode. This
is not a global transport validator rule: when filtering is inactive, batches
pass downstream exactly like any other body.

When neither authorization nor protection is active and no tool registry
is configured, the adapter does not validate, buffer, or replay MCP message
bodies. Invalid JSON-RPC bodies, batch bodies, and bodies larger than
`maxBodyBytes` pass downstream unchanged.

When filtering is active, only a body-size failure raised while the adapter is
reading the message becomes its `413` response. A `DataBufferLimitException`
raised later by downstream handling propagates unchanged. Replayed messages have
conflicting transfer framing removed and an exact `Content-Length` set.

Valid non-tool JSON-RPC methods are parsed as non-authorizable invocations:
authorization is skipped, and protection still runs when enabled.

Invalid message reasons are:

| Reason | Meaning |
| --- | --- |
| `invalid_json_rpc_request` | Body cannot be parsed as one complete JSON value, including duplicate object fields. |
| `batch_not_supported` | Body is a JSON-RPC batch array. |
| `invalid_request_shape` | Body is empty, not an object message, is neither a recognized response nor a request with an exact non-blank string `method`, or has invalid/padded `tools/call` params/name shape. |

`McpGatewayWebFluxProperties` contains:

| Field | Meaning |
| --- | --- |
| `mcpEndpoint` | Application-relative HTTP path receiving MCP JSON-RPC. Default is `/mcp`; route matching is matrix-parameter aware. |
| `maxBodyBytes` | Maximum message body buffered by the adapter filter. Minimum normalized value is `1024`. Default is `262144`. |
| `governanceFilterOrder` | Spring `WebFilter` order for the WebFlux governance filter. |

`McpGatewayAuthorizationMode` values:

| Value | Meaning |
| --- | --- |
| `DISABLED` | Do not evaluate authorization in the adapter. |
| `WARN` | Record warnings but pass denied mapped requests through. |
| `ENFORCE` | Reject denied mapped requests. |

`McpGatewayWebFluxContextResolver` maps Spring `Authentication`, the
`ServerWebExchange`, and the parsed `McpToolInvocation` into
`GatewayToolExecutionContext`.

`McpGrantedScopesExtractor.springSecurityScopes()` reads Spring Security
authorities with the `SCOPE_` prefix only for authenticated, non-anonymous
principals. It trims names, drops blanks, lower-cases, and de-duplicates them.
Null results from a custom extractor normalize to an empty scope list.

`McpAuthorizationObservation` is emitted by the WebFlux governance filter for
authorization decisions, except for registry-aware enforced unmapped-tool
configuration failures, which are not reported as permission denials:

| Field | Meaning |
| --- | --- |
| `actionName` | Evaluated action or tool name. |
| `outcome` | `allowed`, `denied`, or `warn`. |
| `reason` | Low-cardinality reason such as `scope_granted`, `insufficient_scope`, or `unmapped_tool`. |
| `requiredScopes` | Required scopes from the mapped action. |
| `grantedScopes` | Caller scopes considered. |
| `context` | Core execution context. |

### Rejection Responses and Observability

The runtime owns the policy that decides what should be allowed. The adapter's
response formats are deliberately distinct:

| Condition | HTTP status | Response body | Authentication challenge |
| --- | --- | --- | --- |
| Registry configured: unknown or disabled tool | `200` | JSON-RPC `-32602`, `Unknown tool`, original `id` | None |
| Registry configured: available tool with enforced unmapped authorization | `200` | JSON-RPC `-32603`, `Internal error`, original `id` | None |
| Registry configured: invalid tool-call `id` | `400` | JSON-RPC `-32600`, `Invalid Request`, `id: null` | None |
| Registry configured: otherwise well-shaped tool call without an `id` | `202` | Empty; no execution | None |
| Enforced mapped permission denial | `403` | Existing adapter JSON diagnostics, not a JSON-RPC envelope | `Bearer error="insufficient_scope"` |
| No registry: enforced unmapped authorization | `403` | Existing adapter JSON diagnostics (`unmapped_tool`) | Existing insufficient-scope challenge |
| Abuse-protection rejection | `429` | Existing adapter JSON diagnostics, not a JSON-RPC envelope | None |
| Invalid message shape | `400` | Adapter JSON diagnostics (`invalid_json_rpc_request`) | None |
| Adapter request-body limit exceeded | `413` | Existing adapter JSON diagnostics | None |

Non-empty responses in this table use `Content-Type: application/json`.
Registry-aware protocol errors expose only their generic code/message and
JSON-RPC request identifier, not exception details, permission requirements, or
the active tool registry.

Unknown-tool, tool-call identifier, and enforced unmapped-tool configuration
rejections do not emit authorization observations, so they are not mislabeled
as permission denials. This option adds no automatic diagnostic events or new
observer API for these protocol responses. Ordinary mapped permission decisions
retain their existing authorization observations. Runtimes remain responsible
for operational diagnostics, including startup validation of permission mappings.

Existing authorization and protection rejection responses use the execution
context's correlation id when present, otherwise the configured
`McpGatewayCorrelationIdResolver`. Existing invalid-request observations use
the configured resolver. The default resolver applies the core log-safe
correlation-id rules to the request header
and falls back to the server request id. An `insufficient_scope` challenge
includes its `scope` parameter only when every required scope is an RFC 6749
scope token.

## What Not To Encode In Core Values

Do not put these concerns into reusable core contracts:

- product-specific tool names as public library defaults
- private tenant routing rules
- scanner, database, filesystem, or cloud-provider implementation details
- secret values, tokens, or credentials
- high-cardinality metric labels such as raw URLs or user input

Those values belong in the consuming runtime or its adapter layer.
