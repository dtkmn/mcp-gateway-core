package mcp.gateway.core.tool;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable registry of MCP tool descriptors.
 */
public final class McpToolRegistry {
    private final Map<String, McpToolDescriptor> descriptorsByName;

    private McpToolRegistry(Map<String, McpToolDescriptor> descriptorsByName) {
        this.descriptorsByName = Collections.unmodifiableMap(descriptorsByName);
    }

    /**
     * Creates a registry from tool descriptors.
     *
     * @param descriptors descriptors to index
     * @return immutable registry
     */
    public static McpToolRegistry of(Collection<McpToolDescriptor> descriptors) {
        if (descriptors == null || descriptors.isEmpty()) {
            return new McpToolRegistry(Map.of());
        }

        LinkedHashMap<String, McpToolDescriptor> descriptorsByName = new LinkedHashMap<>();
        for (McpToolDescriptor descriptor : descriptors) {
            Objects.requireNonNull(descriptor, "descriptor must not be null");
            McpToolDescriptor previous = descriptorsByName.putIfAbsent(descriptor.name(), descriptor);
            if (previous != null) {
                throw new IllegalArgumentException("duplicate MCP tool descriptor: " + descriptor.name());
            }
        }
        return new McpToolRegistry(descriptorsByName);
    }

    /**
     * Finds a descriptor by tool name.
     *
     * @param toolName tool name
     * @return descriptor when known
     */
    public Optional<McpToolDescriptor> descriptor(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(descriptorsByName.get(toolName.trim()));
    }

    /**
     * Returns a descriptor or fails when the tool is unknown.
     *
     * @param toolName tool name
     * @return descriptor
     */
    public McpToolDescriptor requireDescriptor(String toolName) {
        return descriptor(toolName)
                .orElseThrow(() -> new IllegalArgumentException("unknown MCP tool: " + toolName));
    }

    /**
     * Checks whether a tool is known.
     *
     * @param toolName tool name
     * @return true when known
     */
    public boolean contains(String toolName) {
        return descriptor(toolName).isPresent();
    }

    /**
     * Checks whether a tool has a capability.
     *
     * @param toolName tool name
     * @param capability capability to check
     * @return true when present
     */
    public boolean hasCapability(String toolName, McpToolCapability capability) {
        return descriptor(toolName)
                .map(descriptor -> descriptor.hasCapability(capability))
                .orElse(false);
    }

    /**
     * Checks whether a tool has a capability.
     *
     * @param toolName tool name
     * @param capability capability identifier
     * @return true when present
     */
    public boolean hasCapability(String toolName, String capability) {
        return hasCapability(toolName, McpToolCapability.of(capability));
    }

    /**
     * Returns all descriptors in registration order.
     *
     * @return descriptors
     */
    public List<McpToolDescriptor> descriptors() {
        return List.copyOf(descriptorsByName.values());
    }

    /**
     * Returns all known tool names.
     *
     * @return tool names
     */
    public Set<String> names() {
        return Set.copyOf(descriptorsByName.keySet());
    }

    /**
     * Returns descriptors that belong to a surface.
     *
     * @param surface surface to match
     * @return matching descriptors
     */
    public List<McpToolDescriptor> descriptorsForSurface(McpToolSurface surface) {
        Objects.requireNonNull(surface, "surface must not be null");
        return descriptorsByName.values().stream()
                .filter(descriptor -> descriptor.surface().equals(surface))
                .toList();
    }

    /**
     * Returns tool names that have a capability.
     *
     * @param capability capability to match
     * @return matching tool names
     */
    public Set<String> namesWithCapability(McpToolCapability capability) {
        Objects.requireNonNull(capability, "capability must not be null");
        return descriptorsByName.values().stream()
                .filter(descriptor -> descriptor.hasCapability(capability))
                .map(McpToolDescriptor::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
