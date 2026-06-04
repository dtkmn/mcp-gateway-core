package mcp.gateway.core.authz;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import mcp.gateway.core.tool.McpToolCapability;
import mcp.gateway.core.tool.McpToolDescriptor;
import mcp.gateway.core.tool.McpToolSurface;

/**
 * Authorization metadata for one MCP tool.
 *
 * @param toolName MCP tool name
 * @param surface logical tool surface
 * @param requiredScopes non-empty normalized required scopes
 * @param capabilities normalized capabilities associated with the tool
 */
public record McpToolAccessRule(String toolName,
                                McpToolSurface surface,
                                List<String> requiredScopes,
                                Set<McpToolCapability> capabilities) {

    /**
     * Creates an access rule after validating the tool descriptor and required
     * authorization scopes.
     */
    public McpToolAccessRule {
        McpToolDescriptor descriptor = McpToolDescriptor.of(toolName, surface, capabilities);
        ToolAuthorizationRequirement requirement = ToolAuthorizationRequirement.of(descriptor.name(), requiredScopes);
        toolName = descriptor.name();
        surface = descriptor.surface();
        capabilities = descriptor.capabilities();
        requiredScopes = requirement.requiredScopes();
    }

    /**
     * Creates a rule without additional capabilities.
     *
     * @param toolName MCP tool name
     * @param surface logical tool surface
     * @param requiredScopes required scopes
     * @return access rule
     */
    public static McpToolAccessRule of(String toolName,
                                       McpToolSurface surface,
                                       Collection<String> requiredScopes) {
        return new McpToolAccessRule(
                toolName,
                surface,
                ToolAuthorizationRequest.normalizeScopes(requiredScopes),
                Set.of()
        );
    }

    /**
     * Starts a rule builder.
     *
     * @param toolName MCP tool name
     * @param surface logical tool surface
     * @return builder
     */
    public static Builder builder(String toolName, McpToolSurface surface) {
        return new Builder(toolName, surface);
    }

    /**
     * Converts this rule to a neutral MCP tool descriptor.
     *
     * @return tool descriptor
     */
    public McpToolDescriptor descriptor() {
        return McpToolDescriptor.of(toolName, surface, capabilities);
    }

    /**
     * Converts this rule to an authorization requirement.
     *
     * @return authorization requirement
     */
    public ToolAuthorizationRequirement requirement() {
        return ToolAuthorizationRequirement.of(toolName, requiredScopes);
    }

    /**
     * Builder for MCP tool access rules.
     */
    public static final class Builder {
        private final String toolName;
        private final McpToolSurface surface;
        private final LinkedHashSet<String> requiredScopes = new LinkedHashSet<>();
        private final LinkedHashSet<McpToolCapability> capabilities = new LinkedHashSet<>();

        private Builder(String toolName, McpToolSurface surface) {
            this.toolName = toolName;
            this.surface = surface;
        }

        /**
         * Adds one required scope.
         *
         * @param scope required scope
         * @return this builder
         */
        public Builder requiredScope(String scope) {
            ToolAuthorizationRequest.normalizeScopes(Collections.singletonList(scope)).forEach(requiredScopes::add);
            return this;
        }

        /**
         * Adds required scopes.
         *
         * @param scopes required scopes
         * @return this builder
         */
        public Builder requiredScopes(Collection<String> scopes) {
            ToolAuthorizationRequest.normalizeScopes(scopes).forEach(requiredScopes::add);
            return this;
        }

        /**
         * Adds one capability.
         *
         * @param capability capability
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
         * @param capabilities capabilities
         * @return this builder
         */
        public Builder capabilities(Collection<McpToolCapability> capabilities) {
            if (capabilities != null) {
                capabilities.forEach(this::capability);
            }
            return this;
        }

        /**
         * Builds the access rule.
         *
         * @return access rule
         */
        public McpToolAccessRule build() {
            return new McpToolAccessRule(toolName, surface, List.copyOf(requiredScopes), Set.copyOf(capabilities));
        }
    }
}
