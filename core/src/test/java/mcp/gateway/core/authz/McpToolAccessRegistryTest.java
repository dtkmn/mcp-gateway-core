package mcp.gateway.core.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import mcp.gateway.core.tool.McpToolCapability;
import mcp.gateway.core.tool.McpToolSurface;
import org.junit.jupiter.api.Test;

class McpToolAccessRegistryTest {

    @Test
    void joinsRequiredScopesAndToolMetadata() {
        McpToolAccessRule guided = McpToolAccessRule.builder("demo_guided", McpToolSurface.GUIDED)
                .requiredScope("Tool.Execute")
                .capability("scan.guided")
                .build();
        McpToolAccessRule expert = McpToolAccessRule.builder("demo_expert", McpToolSurface.EXPERT)
                .requiredScopes(List.of("Tool.Read", "tool.read"))
                .capability("queue.admission")
                .build();

        McpToolAccessRegistry registry = McpToolAccessRegistry.of(List.of(guided, expert));

        assertEquals(List.of("tool.execute"), registry.requiredScopes("demo_guided").orElseThrow());
        assertEquals(List.of("tool.read"), registry.requiredScopes("demo_expert").orElseThrow());
        assertEquals(Set.of("demo_expert"), registry.namesWithCapability(McpToolCapability.of("queue.admission")));
        assertTrue(registry.hasCapability("demo_guided", McpToolCapability.of("scan.guided")));
        assertEquals(List.of(guided.descriptor()), registry.descriptorsForSurface(McpToolSurface.GUIDED));
        assertTrue(registry.toolRegistry().contains("demo_expert"));
    }

    @Test
    void treatsMissingToolAsUnmapped() {
        McpToolAccessRegistry registry = McpToolAccessRegistry.of(List.of(
                McpToolAccessRule.builder("demo_tool", McpToolSurface.GUIDED)
                        .requiredScope("tool.execute")
                        .build()
        ));

        assertTrue(registry.requiredScopes("missing_tool").isEmpty());
        assertTrue(registry.requirement(" ").isEmpty());
        assertFalse(registry.hasCapability("missing_tool", McpToolCapability.of("scan.guided")));
    }

    @Test
    void rejectsAmbiguousRules() {
        McpToolAccessRule first = McpToolAccessRule.builder("demo_tool", McpToolSurface.GUIDED)
                .requiredScope("tool.execute")
                .build();
        McpToolAccessRule duplicate = McpToolAccessRule.builder("demo_tool", McpToolSurface.EXPERT)
                .requiredScope("tool.read")
                .build();

        assertThrows(IllegalArgumentException.class, () -> McpToolAccessRegistry.of(List.of(first, duplicate)));
        assertThrows(IllegalArgumentException.class, () -> McpToolAccessRule.builder("empty_scope", McpToolSurface.GUIDED).build());
        assertThrows(IllegalArgumentException.class,
                () -> McpToolAccessRegistry.of(java.util.Arrays.asList(first, null)));
    }

    @Test
    void exposesImmutableScopeMap() {
        McpToolAccessRegistry registry = McpToolAccessRegistry.of(List.of(
                McpToolAccessRule.builder("demo_tool", McpToolSurface.GUIDED)
                        .requiredScope("tool.execute")
                        .build()
        ));

        Map<String, List<String>> scopes = registry.requiredScopesByTool();

        assertEquals(Map.of("demo_tool", List.of("tool.execute")), scopes);
        assertThrows(UnsupportedOperationException.class, () -> scopes.put("other", List.of("tool.read")));
        assertThrows(UnsupportedOperationException.class, () -> scopes.get("demo_tool").add("tool.read"));
    }
}
