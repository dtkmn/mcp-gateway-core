package mcp.gateway.core.authz;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import mcp.gateway.core.tool.McpToolCapability;
import mcp.gateway.core.tool.McpToolDescriptor;
import mcp.gateway.core.tool.McpToolRegistry;
import mcp.gateway.core.tool.McpToolSurface;

/**
 * Immutable registry that joins MCP tool descriptors with required
 * authorization scopes.
 */
public final class McpToolAccessRegistry {
    private final Map<String, ToolAuthorizationRequirement> requirementsByTool;
    private final McpToolRegistry toolRegistry;

    private McpToolAccessRegistry(Map<String, ToolAuthorizationRequirement> requirementsByTool,
                                  McpToolRegistry toolRegistry) {
        this.requirementsByTool = Collections.unmodifiableMap(requirementsByTool);
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
    }

    /**
     * Creates an access registry from tool access rules.
     *
     * @param rules access rules
     * @return immutable access registry
     */
    public static McpToolAccessRegistry of(Collection<McpToolAccessRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return new McpToolAccessRegistry(Map.of(), McpToolRegistry.of(List.of()));
        }

        LinkedHashMap<String, ToolAuthorizationRequirement> requirements = new LinkedHashMap<>();
        List<McpToolDescriptor> descriptors = rules.stream()
                .map(rule -> {
                    if (rule == null) {
                        throw new IllegalArgumentException("access rule must not be null");
                    }
                    McpToolAccessRule accessRule = rule;
                    ToolAuthorizationRequirement previous =
                            requirements.putIfAbsent(accessRule.toolName(), accessRule.requirement());
                    if (previous != null) {
                        throw new IllegalArgumentException("duplicate MCP tool access rule: " + accessRule.toolName());
                    }
                    return accessRule.descriptor();
                })
                .toList();

        return new McpToolAccessRegistry(requirements, McpToolRegistry.of(descriptors));
    }

    /**
     * Finds required scopes for a tool.
     *
     * @param toolName tool name
     * @return normalized scopes when the tool is mapped
     */
    public Optional<List<String>> requiredScopes(String toolName) {
        return requirement(toolName).map(ToolAuthorizationRequirement::requiredScopes);
    }

    /**
     * Finds the authorization requirement for a tool.
     *
     * @param toolName tool name
     * @return requirement when the tool is mapped
     */
    public Optional<ToolAuthorizationRequirement> requirement(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(requirementsByTool.get(toolName.trim()));
    }

    /**
     * Returns all required scopes keyed by tool name in registration order.
     *
     * @return immutable required-scope map
     */
    public Map<String, List<String>> requiredScopesByTool() {
        LinkedHashMap<String, List<String>> scopes = new LinkedHashMap<>();
        requirementsByTool.forEach((tool, requirement) -> scopes.put(tool, requirement.requiredScopes()));
        return Collections.unmodifiableMap(scopes);
    }

    /**
     * Returns the neutral tool descriptor registry.
     *
     * @return tool registry
     */
    public McpToolRegistry toolRegistry() {
        return toolRegistry;
    }

    /**
     * Checks whether a mapped tool has a capability.
     *
     * @param toolName tool name
     * @param capability capability
     * @return true when present
     */
    public boolean hasCapability(String toolName, McpToolCapability capability) {
        return toolRegistry.hasCapability(toolName, capability);
    }

    /**
     * Returns mapped tool names with the supplied capability.
     *
     * @param capability capability
     * @return matching tool names
     */
    public Set<String> namesWithCapability(McpToolCapability capability) {
        return toolRegistry.namesWithCapability(capability);
    }

    /**
     * Returns descriptors for a tool surface.
     *
     * @param surface tool surface
     * @return matching descriptors
     */
    public List<McpToolDescriptor> descriptorsForSurface(McpToolSurface surface) {
        return toolRegistry.descriptorsForSurface(surface);
    }
}
