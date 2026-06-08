package mcp.gateway.core.tool;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * MCP-neutral identity and capability metadata for a tool.
 *
 * @param name tool name as exposed by the MCP server
 * @param surface logical tool surface that owns the tool
 * @param capabilities normalized capabilities associated with the tool
 */
public record McpToolDescriptor(String name,
                                McpToolSurface surface,
                                Set<McpToolCapability> capabilities) {

    /**
     * Creates a descriptor after validating the tool identity and metadata.
     */
    public McpToolDescriptor {
        name = normalizeName(name);
        surface = Objects.requireNonNull(surface, "surface must not be null");
        capabilities = immutableCapabilities(capabilities);
    }

    /**
     * Creates a descriptor from a collection of capabilities.
     *
     * @param name tool name
     * @param surface tool surface
     * @param capabilities capabilities to attach
     * @return descriptor
     */
    public static McpToolDescriptor of(String name,
                                       McpToolSurface surface,
                                       Collection<McpToolCapability> capabilities) {
        return new McpToolDescriptor(name, surface, capabilities == null ? Set.of() : Set.copyOf(capabilities));
    }

    /**
     * Starts a descriptor builder.
     *
     * @param name tool name
     * @param surface tool surface
     * @return descriptor builder
     */
    public static Builder builder(String name, McpToolSurface surface) {
        return new Builder(name, surface);
    }

    /**
     * Checks whether this tool has the supplied capability.
     *
     * @param capability capability to check
     * @return true when present
     */
    public boolean hasCapability(McpToolCapability capability) {
        return capabilities.contains(Objects.requireNonNull(capability, "capability must not be null"));
    }

    /**
     * Checks whether this tool has the supplied capability.
     *
     * @param capability capability identifier
     * @return true when present
     */
    public boolean hasCapability(String capability) {
        return hasCapability(McpToolCapability.of(capability));
    }

    private static Set<McpToolCapability> immutableCapabilities(Set<McpToolCapability> capabilities) {
        if (capabilities == null || capabilities.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<McpToolCapability> copy = new LinkedHashSet<>();
        for (McpToolCapability capability : capabilities) {
            copy.add(Objects.requireNonNull(capability, "capability must not be null"));
        }
        return Collections.unmodifiableSet(copy);
    }

    private static String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tool name must not be blank");
        }
        String normalized = name.trim();
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            boolean valid = character >= 'a' && character <= 'z'
                    || character >= 'A' && character <= 'Z'
                    || character >= '0' && character <= '9'
                    || character == '_'
                    || character == '-'
                    || character == '.'
                    || character == ':'
                    || character == '/';
            if (!valid) {
                throw new IllegalArgumentException("tool name contains unsupported character: " + name);
            }
        }
        return normalized;
    }

    /**
     * Builder for tool descriptors.
     */
    public static final class Builder {
        private final String name;
        private final McpToolSurface surface;
        private final LinkedHashSet<McpToolCapability> capabilities = new LinkedHashSet<>();

        private Builder(String name, McpToolSurface surface) {
            this.name = name;
            this.surface = surface;
        }

        /**
         * Adds one capability.
         *
         * @param capability capability to add
         * @return this builder
         */
        public Builder capability(McpToolCapability capability) {
            capabilities.add(Objects.requireNonNull(capability, "capability must not be null"));
            return this;
        }

        /**
         * Adds one capability.
         *
         * @param capability capability identifier
         * @return this builder
         */
        public Builder capability(String capability) {
            return capability(McpToolCapability.of(capability));
        }

        /**
         * Adds capabilities.
         *
         * @param capabilities capabilities to add
         * @return this builder
         */
        public Builder capabilities(Collection<McpToolCapability> capabilities) {
            if (capabilities != null) {
                capabilities.forEach(this::capability);
            }
            return this;
        }

        /**
         * Builds the descriptor.
         *
         * @return descriptor
         */
        public McpToolDescriptor build() {
            return new McpToolDescriptor(name, surface, capabilities);
        }
    }
}
