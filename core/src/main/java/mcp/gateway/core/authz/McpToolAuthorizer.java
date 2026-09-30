package mcp.gateway.core.authz;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import mcp.gateway.core.context.GatewayToolExecutionContext;
import mcp.gateway.core.invocation.McpToolInvocationKind;

/**
 * MCP-neutral authorizer for tool-list and tool-call requests.
 * <p>
 * Runtime projects still own caller authentication, scope assignment, and the
 * product-specific tool-to-scope catalog. This class owns the common
 * authorization flow once those inputs are known.
 */
public final class McpToolAuthorizer {
    /** Synthetic action used by gateway controls for MCP {@code tools/list}. */
    public static final String TOOLS_LIST_ACTION = "mcp:tools:list";
    /** Action marker used when an invocation name is unavailable or not authorizable. */
    public static final String UNKNOWN_ACTION = "unknown";

    private final McpToolAccessRegistry registry;
    private final ToolAuthorizationRequirement toolsListRequirement;

    private McpToolAuthorizer(McpToolAccessRegistry registry,
                              ToolAuthorizationRequirement toolsListRequirement) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.toolsListRequirement = Objects.requireNonNull(
                toolsListRequirement,
                "toolsListRequirement must not be null"
        );
    }

    /**
     * Creates an authorizer from a tool registry and the scopes required for
     * {@code tools/list}.
     *
     * @param registry tool access registry
     * @param toolsListRequiredScopes required scopes for listing tools
     * @return authorizer
     */
    public static McpToolAuthorizer of(McpToolAccessRegistry registry,
                                       Collection<String> toolsListRequiredScopes) {
        return new McpToolAuthorizer(
                registry,
                ToolAuthorizationRequirement.of(TOOLS_LIST_ACTION, toolsListRequiredScopes)
        );
    }

    /**
     * Evaluates permissions for a normalized gateway tool context using strict defaults.
     * <p>
     * Permission checks are enabled and {@code *} is not treated as a universal
     * grant. Every mapped required scope must be present in the caller's scopes.
     * Null or non-authorizable contexts produce an unmapped, denied decision.
     * <p>
     * This method returns a permission decision; it does not stop execution.
     * When composed with governance, the governance policy owns whether checks
     * run and whether denied or unmapped decisions reject or warn. The argument
     * order also supports the WebFlux builder's authorization callback directly.
     * Use the four-argument overload for an explicit dynamic or wildcard policy.
     *
     * @param grantedScopes scopes granted to the caller
     * @param context tool context, or null for an unmapped decision
     * @return decision
     */
    public ToolAuthorizationDecision authorize(Collection<String> grantedScopes,
                                               GatewayToolExecutionContext context) {
        return authorize(context, grantedScopes, false, true);
    }

    /**
     * Authorizes a normalized gateway tool context.
     * <p>
     * Unknown or non-authorizable contexts are returned as unmapped decisions.
     * {@code tools/list} is evaluated against the list requirement configured on
     * this authorizer; {@code tools/call} is evaluated against the named tool's
     * registry entry.
     * <p>
     * These flags control the low-level permission decision independently of
     * any enclosing governance policy. Prefer the two-argument overload when
     * the governance policy owns activation and enforcement and universal
     * wildcard grants are not required.
     *
     * @param context tool context
     * @param grantedScopes scopes granted to the caller
     * @param wildcardAllowed whether {@code *} grants all mapped required scopes
     * @param authorizationEnabled whether mapped requirements should be enforced
     *        instead of treated as an allowed mapped decision
     * @return decision
     */
    public ToolAuthorizationDecision authorize(GatewayToolExecutionContext context,
                                               Collection<String> grantedScopes,
                                               boolean wildcardAllowed,
                                               boolean authorizationEnabled) {
        if (context == null || !context.invocation().authorizable()) {
            return evaluate(UNKNOWN_ACTION, grantedScopes, null, wildcardAllowed, authorizationEnabled);
        }
        if (context.invocation().kind() == McpToolInvocationKind.TOOLS_LIST) {
            return authorizeToolsList(grantedScopes, wildcardAllowed, authorizationEnabled);
        }
        if (context.invocation().kind() == McpToolInvocationKind.TOOL_CALL) {
            return authorizeToolCall(context.toolName(), grantedScopes, wildcardAllowed, authorizationEnabled);
        }
        return evaluate(UNKNOWN_ACTION, grantedScopes, null, wildcardAllowed, authorizationEnabled);
    }

    /**
     * Authorizes one MCP tool call.
     * <p>
     * Blank tool names are reported with the synthetic {@link #UNKNOWN_ACTION}
     * action. Unknown non-blank names are preserved in the unmapped decision so
     * audit and governance observers can identify the requested tool.
     *
     * @param toolName MCP tool name
     * @param grantedScopes scopes granted to the caller
     * @param wildcardAllowed whether {@code *} grants all mapped required scopes
     * @param authorizationEnabled whether mapped requirements should be enforced
     *        instead of treated as an allowed mapped decision
     * @return decision
     */
    public ToolAuthorizationDecision authorizeToolCall(String toolName,
                                                       Collection<String> grantedScopes,
                                                       boolean wildcardAllowed,
                                                       boolean authorizationEnabled) {
        ToolAuthorizationRequirement requirement = registry.requirement(toolName).orElse(null);
        return evaluate(normalizeActionName(toolName), grantedScopes, requirement, wildcardAllowed, authorizationEnabled);
    }

    /**
     * Authorizes an MCP {@code tools/list} request.
     * <p>
     * The action name is always {@link #TOOLS_LIST_ACTION}, so callers can keep
     * tool listing requirements separate from individual tool-call requirements.
     *
     * @param grantedScopes scopes granted to the caller
     * @param wildcardAllowed whether {@code *} grants all mapped required scopes
     * @param authorizationEnabled whether mapped requirements should be enforced
     *        instead of treated as an allowed mapped decision
     * @return decision
     */
    public ToolAuthorizationDecision authorizeToolsList(Collection<String> grantedScopes,
                                                        boolean wildcardAllowed,
                                                        boolean authorizationEnabled) {
        return evaluate(
                TOOLS_LIST_ACTION,
                grantedScopes,
                toolsListRequirement,
                wildcardAllowed,
                authorizationEnabled
        );
    }

    private ToolAuthorizationDecision evaluate(String actionName,
                                               Collection<String> grantedScopes,
                                               ToolAuthorizationRequirement requirement,
                                               boolean wildcardAllowed,
                                               boolean authorizationEnabled) {
        ToolAuthorizationRequest request = ToolAuthorizationRequest.of(actionName, grantedScopes, wildcardAllowed);
        if (requirement == null) {
            return ToolAuthorizationPipeline.evaluate(request, null);
        }
        if (!authorizationEnabled) {
            return new ToolAuthorizationDecision(
                    true,
                    true,
                    request.actionName(),
                    requirement.requiredScopes(),
                    request.grantedScopes(),
                    List.of()
            );
        }
        return ToolAuthorizationPipeline.evaluate(request, requirement);
    }

    private static String normalizeActionName(String actionName) {
        if (actionName == null || actionName.isBlank()) {
            return UNKNOWN_ACTION;
        }
        return actionName.trim();
    }
}
