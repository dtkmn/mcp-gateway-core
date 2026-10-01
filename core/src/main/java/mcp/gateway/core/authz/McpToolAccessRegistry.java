package mcp.gateway.core.authz;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
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
     * Selects an immutable tool registry for exactly the supplied runtime names,
     * after verifying that every name has a permission mapping.
     * <p>
     * The host must supply the names of its actually registered, enabled tools.
     * This method does not discover or register tools, and unused access rules
     * do not become active. Names are matched exactly and case-sensitively;
     * null, blank, padded, and duplicate names are rejected rather than normalized.
     * All missing permission mappings are reported together in sorted order.
     * <p>
     * Existing descriptors and their capabilities are retained. Descriptor order
     * follows the supplied names' encounter order. An empty collection produces
     * an empty registry; subsequent changes to the input collection do not affect it.
     *
     * @param exposedToolNames actual names exposed by the hosting runtime
     * @return immutable registry containing only the supplied tools
     * @throws NullPointerException when the collection is null
     * @throws IllegalArgumentException for invalid or duplicate names, or missing
     *         permission mappings
     */
    public McpToolRegistry activeToolRegistry(Collection<String> exposedToolNames) {
        Objects.requireNonNull(exposedToolNames, "exposedToolNames must not be null");
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String name : exposedToolNames) {
            if (name == null || name.isBlank() || !name.equals(name.strip()) || !name.equals(name.trim())) {
                throw new IllegalArgumentException("exposed MCP tool names must be non-null, non-blank, and unpadded");
            }
            if (!names.add(name)) {
                throw new IllegalArgumentException("duplicate exposed MCP tool name: " + name);
            }
        }

        Set<String> missingNames = new TreeSet<>();
        for (String name : names) {
            if (!requirementsByTool.containsKey(name)) {
                missingNames.add(name);
            }
        }
        if (!missingNames.isEmpty()) {
            throw new IllegalArgumentException("Missing permission mappings for MCP tools: " + missingNames);
        }
        return McpToolRegistry.of(names.stream().map(toolRegistry::requireDescriptor).toList());
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
