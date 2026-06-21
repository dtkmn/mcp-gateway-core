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
    /** Action marker used when an invocation is unavailable or not authorizable. */
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
     * Authorizes a normalized gateway tool context.
     * <p>
     * Unknown or non-authorizable contexts are returned as unmapped decisions.
     * {@code tools/list} is evaluated against the list requirement configured on
     * this authorizer; {@code tools/call} is evaluated against the named tool's
     * registry entry.
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
     * Blank or unknown tool names are reported with the synthetic
     * {@link #UNKNOWN_ACTION} action and an unmapped decision.
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
