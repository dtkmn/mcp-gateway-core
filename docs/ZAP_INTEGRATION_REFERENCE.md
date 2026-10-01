# Spring AI/WebFlux Integration Reference

Use [MCP ZAP Server](https://github.com/dtkmn/mcp-zap-server) as a working
reference when embedding Core and its WebFlux adapter into a Spring AI MCP
server. Start with [Getting Started](https://danieltse.org/mcp-gateway-core/guides/getting-started/)
for the APIs and minimal wiring. This optional reference shows how one consumer
connects them and verifies permission enforcement through MCP transport.

The integration uses published Core/WebFlux `0.11.0` artifacts. It requires a
ZAP development checkout with `gatewayCoreVersion=0.11.0` and the test methods
below; released ZAP `v0.13.0` still uses Gateway `0.10.0`. Build the application
with JDK 25 and its Gradle wrapper. The Gateway libraries themselves target
Java 17. See ZAP's [contribution guide](https://github.com/dtkmn/mcp-zap-server/blob/dev/CONTRIBUTING.md)
for application setup and full build checks.

## Trace The Wiring

These links point to ZAP's development source. Follow the handoffs when adapting
the pattern to your own server:

| Handoff | ZAP implementation | Pattern to reuse |
| --- | --- | --- |
| Register tools | [McpServerApplication](https://github.com/dtkmn/mcp-zap-server/blob/dev/src/main/java/mcp/server/zap/McpServerApplication.java) | Obtain the actual tools from Spring AI's registration provider. |
| Assemble governance | [McpGatewayWebFluxAdapterConfiguration](https://github.com/dtkmn/mcp-zap-server/blob/dev/src/main/java/mcp/server/zap/core/configuration/McpGatewayWebFluxAdapterConfiguration.java) | Feed names from that same provider to `activeToolRegistry`; missing permission mappings fail startup. |
| Authenticate and resolve context | [SecurityConfig](https://github.com/dtkmn/mcp-zap-server/blob/dev/src/main/java/mcp/server/zap/core/configuration/SecurityConfig.java) and [ClientWorkspaceResolver](https://github.com/dtkmn/mcp-zap-server/blob/dev/src/main/java/mcp/server/zap/core/service/protection/ClientWorkspaceResolver.java) | Authenticate before governance; supply trusted caller/workspace identities and preserve the adapter's parsed invocation. Availability is checked before context resolution. |
| Supply application policy | [ToolAuthorizationService](https://github.com/dtkmn/mcp-zap-server/blob/dev/src/main/java/mcp/server/zap/core/service/authz/ToolAuthorizationService.java) and [McpAbuseProtectionService](https://github.com/dtkmn/mcp-zap-server/blob/dev/src/main/java/mcp/server/zap/core/service/protection/McpAbuseProtectionService.java) | Keep permission choices, authorization modes, rate keys and domain quotas in the host. |
| Observe decisions | WebFlux configuration and [ObservabilityService](https://github.com/dtkmn/mcp-zap-server/blob/dev/src/main/java/mcp/server/zap/core/observability/ObservabilityService.java) | Compose metrics-only callbacks with `McpGatewayAuditObservers`, making one audit-sink attempt per signal. |
| Retain audits and observe completion | [AuditEventStream](https://github.com/dtkmn/mcp-zap-server/blob/dev/src/main/java/mcp/server/zap/core/observability/AuditEventStream.java) and [ToolExecutionObservabilityAspect](https://github.com/dtkmn/mcp-zap-server/blob/dev/src/main/java/mcp/server/zap/core/observability/ToolExecutionObservabilityAspect.java) | Keep the audit backend and tool-completion observations application-owned. |

Copy the handoff pattern, then supply your own tool catalog, identity resolution,
policies and audit backend. ZAP's scan services and quotas are consumer-specific;
they are not requirements for using Core.

## Verify Allowed And Denied Execution

From that ZAP checkout, run:

```bash
./gradlew test \
  --tests 'mcp.server.zap.core.configuration.McpToolVisibilityIntegrationTest.activeToolExecutesOnceWithTheRequiredPermission' \
  --tests 'mcp.server.zap.core.configuration.McpToolVisibilityIntegrationTest.activeToolStillRequiresItsPermissionAndDoesNotExecuteWhenDenied' \
  --no-daemon
```

The [test fixture](https://github.com/dtkmn/mcp-zap-server/blob/dev/src/test/java/mcp/server/zap/core/configuration/McpToolVisibilityIntegrationTest.java)
starts the configured Spring WebFlux/Spring AI server on a random local port,
initializes a Streamable HTTP session, and calls `zap_passive_scan_status`.
Synthetic API keys provide two callers:

| Caller | Expected evidence |
| --- | --- |
| Reader with `mcp:tools:list` and `zap:scan:read` | HTTP `200`; handler called once; one allowed authorization audit and a separate tool-completion audit. |
| Lister with only `mcp:tools:list` | HTTP `403` / `insufficient_scope`; no handler or protection evaluation; one denied authorization audit and no tool-completion audit. |

Assertions also check trusted context, correlation, metric changes and audit
counts. Inspect `build/reports/tests/test/index.html` for results. The exercise
uses a mocked engine: no ZAP process, database, real credentials or external scan
target is needed. It verifies the configured MCP boundary, not a live scan or an
external identity provider.

Governance audits describe decisions before execution. Keep completion records
separate and review the [shared audit contract](https://danieltse.org/mcp-gateway-core/reference/contract-reference/#audit-observer-helper-unreleased)
when migrating existing audit consumers. For ZAP's storage and schema choices,
see its [observability guide](https://danieltse.org/mcp-zap-server/operations/observability/).
