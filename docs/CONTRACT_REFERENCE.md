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
- Scope strings normalize to lower case and are de-duplicated in insertion order.
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
| `requiredScopes` | Non-empty scopes required to invoke the tool. |
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
requirements are invalid. Use a missing requirement to represent an unmapped
action.

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
| `tools` | Exact MCP tool names. |
| `hosts` | Exact or wildcard host patterns. |
| `timeWindows` | Bundle-local day/time windows. |

Blank selector entries are rejected instead of silently broadening a rule.

`PolicyBundleTimeWindow` contains:

| Field | Meaning |
| --- | --- |
| `days` | Matching `DayOfWeek` values. |
| `start` | Inclusive local start time. |
| `end` | Exclusive local end time. |

Windows may wrap midnight when `start` is after `end`. Equal start/end is
invalid.

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
| `maxTrackedKeys` | Maximum bucket keys retained in memory. Minimum normalized value is `100`. |
| `disabledRetryAfterSeconds` | Retry delay returned when the policy is disabled. |

When the limiter is at `maxTrackedKeys` and no stale key can be evicted, new
fresh keys fail closed instead of growing memory.

## URL Scope And Correlation IDs

Package: `mcp.gateway.core.url`

`UrlScope` checks whether a candidate URL stays inside an allowed base URL. It
is useful for product runtimes that need target or callback confinement. Core
does not fetch URLs.

Package: `mcp.gateway.core.logging`

`CorrelationIds` defines:

| Value | Meaning |
| --- | --- |
| `X-Correlation-Id` | Preferred request correlation header. |
| `X-Request-Id` | Legacy/fallback request id header. |

## Spring WebFlux Adapter

Package: `mcp.gateway.spring.webflux`

The adapter parses MCP JSON-RPC requests from a WebFlux exchange and applies
core decisions before the request reaches your MCP runtime. It is not Spring
Boot auto-configuration.

Governance is active when authorization policy is enabled or abuse protection is
enabled. When governance is active, the adapter rejects invalid MCP JSON-RPC
request shapes before principal lookup, context resolution, scope extraction,
authorization, protection, or downstream body replay. Rejected invalid shapes
return adapter JSON with HTTP `400`, `Content-Type: application/json`, `error`
set to `invalid_json_rpc_request`, a low-cardinality `reason`, ISO-8601
`timestamp`, resolved `correlationId`, and the server request id as `requestId`.
JSON-RPC `id` is never reflected as `requestId`.

For `0.7.0`, the adapter does not require or validate the JSON-RPC `jsonrpc`
version field. That protocol validation remains the downstream runtime's
responsibility.

When neither authorization nor protection governance is active, the adapter does
not validate, buffer, or replay MCP request bodies. Invalid JSON-RPC bodies,
batch bodies, and bodies larger than `maxBodyBytes` pass downstream unchanged.

Valid non-tool JSON-RPC methods are parsed as non-authorizable invocations:
authorization is skipped, and protection still runs when enabled.

Invalid request reasons are:

| Reason | Meaning |
| --- | --- |
| `invalid_json_rpc_request` | Body cannot be parsed as one complete JSON value. |
| `batch_not_supported` | Body is a JSON-RPC batch array. |
| `invalid_request_shape` | Body is empty, not an object request, lacks a non-blank string `method`, or has invalid `tools/call` params/name shape. |

`McpGatewayWebFluxProperties` contains:

| Field | Meaning |
| --- | --- |
| `mcpEndpoint` | HTTP path receiving MCP JSON-RPC. Default is `/mcp`. |
| `maxBodyBytes` | Maximum request body buffered by the adapter filter. Minimum normalized value is `1024`. Default is `262144`. |
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
authorities with the `SCOPE_` prefix and converts them into normalized scope
names.

`McpAuthorizationObservation` is emitted by the WebFlux governance filter when
authorization runs:

| Field | Meaning |
| --- | --- |
| `actionName` | Evaluated action or tool name. |
| `outcome` | `allowed`, `denied`, or `warn`. |
| `reason` | Low-cardinality reason such as `scope_granted`, `insufficient_scope`, or `unmapped_tool`. |
| `requiredScopes` | Required scopes from the mapped action. |
| `grantedScopes` | Caller scopes considered. |
| `context` | Core execution context. |

The adapter returns MCP-style JSON-RPC errors for authorization and protection
rejections, but your runtime still owns the policy that decides what should be
allowed.

## What Not To Encode In Core Values

Do not put these concerns into reusable core contracts:

- product-specific tool names as public library defaults
- private tenant routing rules
- scanner, database, filesystem, or cloud-provider implementation details
- secret values, tokens, or credentials
- high-cardinality metric labels such as raw URLs or user input

Those values belong in the consuming runtime or its adapter layer.
