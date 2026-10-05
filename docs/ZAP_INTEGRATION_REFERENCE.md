# Spring AI/WebFlux Integration Reference

Use [MCP ZAP Server](https://github.com/dtkmn/mcp-zap-server) as a working
reference when embedding Core and its WebFlux adapter into a Spring AI MCP
server. Start with [Getting Started](https://danieltse.org/mcp-gateway-core/guides/getting-started/)
for the APIs and minimal wiring. This optional reference shows how one consumer
connects them and verifies permission enforcement through MCP transport.

This reference uses released [ZAP `v0.14.0`](https://github.com/dtkmn/mcp-zap-server/releases/tag/v0.14.0),
source commit `87260f38468e21e547c459fb4dd44fec551b0b10`. That release consumes
published Core and WebFlux adapter `0.11.0` artifacts from Maven Central.
Build ZAP with JDK 25 and its Gradle wrapper; the Gateway libraries themselves
target Java 17. No Core source checkout, local artifact publication or running
ZAP engine is required for the exercises below. See ZAP's
[contribution guide](https://github.com/dtkmn/mcp-zap-server/blob/87260f38468e21e547c459fb4dd44fec551b0b10/CONTRIBUTING.md)
for application setup and full build checks.

## Trace The Wiring

These links are pinned to the released consumer source. Follow the handoffs
when adapting the pattern to your own server:

| Handoff | ZAP implementation | Pattern to reuse |
| --- | --- | --- |
| Register tools | [McpServerApplication](https://github.com/dtkmn/mcp-zap-server/blob/87260f38468e21e547c459fb4dd44fec551b0b10/src/main/java/mcp/server/zap/McpServerApplication.java) | Obtain the actual tools from Spring AI's registration provider. |
| Assemble governance | [McpGatewayWebFluxAdapterConfiguration](https://github.com/dtkmn/mcp-zap-server/blob/87260f38468e21e547c459fb4dd44fec551b0b10/src/main/java/mcp/server/zap/core/configuration/McpGatewayWebFluxAdapterConfiguration.java) | Feed names from that same provider to `activeToolRegistry`; missing permission mappings fail startup. |
| Authenticate and resolve context | [SecurityConfig](https://github.com/dtkmn/mcp-zap-server/blob/87260f38468e21e547c459fb4dd44fec551b0b10/src/main/java/mcp/server/zap/core/configuration/SecurityConfig.java) and [ClientWorkspaceResolver](https://github.com/dtkmn/mcp-zap-server/blob/87260f38468e21e547c459fb4dd44fec551b0b10/src/main/java/mcp/server/zap/core/service/protection/ClientWorkspaceResolver.java) | Authenticate before governance; supply trusted caller/workspace identities and preserve the adapter's parsed invocation. Availability is checked before context resolution. |
| Supply application policy | [ToolScopeRegistry](https://github.com/dtkmn/mcp-zap-server/blob/87260f38468e21e547c459fb4dd44fec551b0b10/src/main/java/mcp/server/zap/core/service/authz/ToolScopeRegistry.java), [ToolAuthorizationService](https://github.com/dtkmn/mcp-zap-server/blob/87260f38468e21e547c459fb4dd44fec551b0b10/src/main/java/mcp/server/zap/core/service/authz/ToolAuthorizationService.java) and [McpAbuseProtectionService](https://github.com/dtkmn/mcp-zap-server/blob/87260f38468e21e547c459fb4dd44fec551b0b10/src/main/java/mcp/server/zap/core/service/protection/McpAbuseProtectionService.java) | Map tool names to required scopes, evaluate supplied grants, and keep authorization modes, rate keys and domain quotas in the host. |
| Observe decisions | WebFlux configuration and [ObservabilityService](https://github.com/dtkmn/mcp-zap-server/blob/87260f38468e21e547c459fb4dd44fec551b0b10/src/main/java/mcp/server/zap/core/observability/ObservabilityService.java) | Compose metrics-only callbacks with `McpGatewayAuditObservers`, making one audit-sink attempt per signal. |
| Retain audits and observe completion | [AuditEventStream](https://github.com/dtkmn/mcp-zap-server/blob/87260f38468e21e547c459fb4dd44fec551b0b10/src/main/java/mcp/server/zap/core/observability/AuditEventStream.java) and [ToolExecutionObservabilityAspect](https://github.com/dtkmn/mcp-zap-server/blob/87260f38468e21e547c459fb4dd44fec551b0b10/src/main/java/mcp/server/zap/core/observability/ToolExecutionObservabilityAspect.java) | Keep the audit backend and tool-completion observations application-owned. |

Copy the handoff pattern, then supply your own tool catalog, identity resolution,
policies and audit backend. ZAP's scan services and quotas are consumer-specific;
they are not requirements for using Core.

Core supplies the invocation, context, permission, protection and audit contracts.
The WebFlux adapter parses MCP requests, checks availability and governance,
and writes its rejection responses. Spring Security and the host application
own authentication, granted scopes and workspace selection. ZAP represents
grants as Spring Security `SCOPE_` authorities, which the adapter's default
extractor reads. ZAP configures authorization as `ENFORCE`, `WARN` or `OFF`,
mapping `OFF` to the adapter's `DISABLED` mode. Only `ENFORCE` stops execution
for insufficient permissions. The examples below use `ENFORCE`.

## Verify Allowed And Denied Execution

Use a clean checkout of ZAP `v0.14.0`. With JDK 25 selected, run these commands
from a directory where `mcp-zap-gateway-reference` does not already exist:

```bash
git clone --branch v0.14.0 --depth 1 https://github.com/dtkmn/mcp-zap-server.git mcp-zap-gateway-reference
cd mcp-zap-gateway-reference
git rev-parse HEAD
```

The commit should be `87260f38468e21e547c459fb4dd44fec551b0b10`. Run the three
transport exercises from that checkout:

```bash
./gradlew test \
  --tests 'mcp.server.zap.core.configuration.McpToolVisibilityIntegrationTest.activeToolExecutesOnceWithTheRequiredPermission' \
  --tests 'mcp.server.zap.core.configuration.McpToolVisibilityIntegrationTest.activeToolStillRequiresItsPermissionAndDoesNotExecuteWhenDenied' \
  --tests 'mcp.server.zap.core.configuration.McpToolVisibilityIntegrationTest.protectionRejectionPublishesOneSignalAfterAuthorizationAndPreservesMetrics' \
  --no-daemon
```

The [test fixture](https://github.com/dtkmn/mcp-zap-server/blob/87260f38468e21e547c459fb4dd44fec551b0b10/src/test/java/mcp/server/zap/core/configuration/McpToolVisibilityIntegrationTest.java)
starts the configured Spring WebFlux/Spring AI server on a random local port,
initializes a Streamable HTTP session, and calls `zap_passive_scan_status`.
Synthetic API keys provide two callers:

| Exercise | Expected evidence |
| --- | --- |
| Reader with `mcp:tools:list` and `zap:scan:read` | HTTP `200`; original JSON-RPC `id`, `result.isError=false`; one passive-scan engine-access call, one protection evaluation, one allowed authorization audit and a separate tool-completion audit. |
| Lister with only `mcp:tools:list` | HTTP `403`, diagnostic JSON with `error=insufficient_scope`, and a `WWW-Authenticate` challenge naming `zap:scan:read`; no engine access or protection evaluation; one denied authorization audit and no tool-completion audit. |
| Reader with an injected protection rejection | HTTP `429`, `Retry-After: 37`, and diagnostic JSON with `retryAfterSeconds=37`; one allowed authorization audit followed by one protection-rejection audit; no engine access or tool-completion audit. |

Assertions also check trusted context, correlation, metric changes and audit
counts. Gradle should report a successful build with three passing tests in
`build/reports/tests/test/index.html`. The exercises use a mocked engine: no ZAP
process, database, real credentials, `.env` file or external scan target is
needed. They verify the configured MCP boundary, not a live scan or an external
identity provider. The injected protection rejection verifies response and audit
wiring; it does not demonstrate exhaustion of a production rate limiter.

## Interpret Errors And Correlation

The `403` and `429` responses above are adapter diagnostic JSON, not JSON-RPC
error envelopes. For `403`, correct the caller's granted permissions rather than
retrying unchanged credentials. For `429`, respect `Retry-After`; a successful
permission check does not override protection. Unknown or disabled tools have
different protocol responses; see the full
[rejection contract](https://danieltse.org/mcp-gateway-core/reference/contract-reference/#rejection-responses-and-observability).

The exercises send `X-Correlation-Id` values and assert their presence in trusted
contexts and audits. ZAP's
[request correlation filter](https://github.com/dtkmn/mcp-zap-server/blob/87260f38468e21e547c459fb4dd44fec551b0b10/src/main/java/mcp/server/zap/core/logging/RequestCorrelationWebFilter.java)
sanitizes or creates a correlation id and writes the response header. These
three tests do not explicitly assert that response header. Keep correlation ids,
server HTTP request ids and JSON-RPC `id` values distinct; the audit diagnostic
`requestId` identifies the server HTTP request.

Governance audits describe decisions before execution. Keep completion records
separate and review the [shared audit contract](https://danieltse.org/mcp-gateway-core/reference/contract-reference/#audit-observer-helper-unreleased)
when migrating existing audit consumers. For ZAP's storage and schema choices,
see its [observability guide](https://danieltse.org/mcp-zap-server/operations/observability/).
