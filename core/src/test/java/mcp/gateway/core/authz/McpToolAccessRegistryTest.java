package mcp.gateway.core.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mcp.gateway.core.tool.McpToolCapability;
import mcp.gateway.core.tool.McpToolRegistry;
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

    @Test
    void activeRegistrySelectsOnlyExposedToolsWithOriginalMetadataAndEncounterOrder() {
        McpToolAccessRegistry access = McpToolAccessRegistry.of(List.of(
                McpToolAccessRule.builder("read", McpToolSurface.GUIDED)
                        .requiredScope("files:read").capability("files.read").build(),
                McpToolAccessRule.of("inactive", McpToolSurface.EXPERT, List.of("files:admin")),
                McpToolAccessRule.builder("write", McpToolSurface.EXPERT)
                        .requiredScope("files:write").capability("files.write").build()
        ));
        List<String> exposed = new ArrayList<>(List.of("write", "read"));

        McpToolRegistry active = access.activeToolRegistry(exposed);
        exposed.clear();

        assertEquals(List.of(access.toolRegistry().requireDescriptor("write"),
                access.toolRegistry().requireDescriptor("read")), active.descriptors());
        assertSame(access.toolRegistry().requireDescriptor("write"), active.requireDescriptor("write"));
        assertTrue(active.hasCapability("write", "files.write"));
        assertEquals(McpToolSurface.EXPERT, active.requireDescriptor("write").surface());
        assertFalse(active.contains("inactive"));
        assertTrue(access.requirement("inactive").isPresent());
        assertTrue(access.toolRegistry().contains("inactive"));
        assertThrows(UnsupportedOperationException.class, () -> active.descriptors().clear());
        assertThrows(UnsupportedOperationException.class, () -> active.names().clear());
        assertThrows(UnsupportedOperationException.class, () -> active.requireDescriptor("write").capabilities().clear());
    }

    @Test
    void emptyRuntimeCatalogDoesNotExposeUnusedPolicies() {
        McpToolAccessRegistry access = oneToolRegistry();

        McpToolRegistry active = access.activeToolRegistry(List.of());

        assertEquals(List.of(), active.descriptors());
        assertEquals(Set.of(), active.names());
        assertTrue(access.toolRegistry().contains("known"));
    }

    @Test
    void reportsEveryMissingPermissionMappingInDeterministicSortedOrder() {
        McpToolAccessRegistry access = oneToolRegistry();

        for (List<String> exposed : List.of(List.of("zeta", "known", "alpha", "middle"),
                List.of("middle", "alpha", "known", "zeta"))) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> access.activeToolRegistry(exposed));
            assertEquals("Missing permission mappings for MCP tools: [alpha, middle, zeta]", error.getMessage());
        }
    }

    @Test
    void rejectsNullBlankPaddedAndDuplicateRuntimeNamesBeforeCheckingCoverage() {
        McpToolAccessRegistry access = oneToolRegistry();

        assertThrows(NullPointerException.class, () -> access.activeToolRegistry(null));
        for (String name : Arrays.asList(null, "", " ", "\t", " known", "known ", "\tknown", "known\u2000", "known" + (char) 0)) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> access.activeToolRegistry(Arrays.asList("unmapped", name)));
            assertEquals("exposed MCP tool names must be non-null, non-blank, and unpadded", error.getMessage());
        }
        for (String name : List.of("known", "unmapped")) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> access.activeToolRegistry(List.of(name, name)));
            assertEquals("duplicate exposed MCP tool name: " + name, error.getMessage());
        }
    }

    @Test
    void runtimeToolIdentityIsCaseSensitive() {
        McpToolAccessRegistry access = McpToolAccessRegistry.of(List.of(
                McpToolAccessRule.of("read", McpToolSurface.GUIDED, List.of("files:read")),
                McpToolAccessRule.of("Read", McpToolSurface.EXPERT, List.of("files:admin"))
        ));

        McpToolRegistry active = access.activeToolRegistry(List.of("Read"));

        assertEquals(Set.of("Read"), active.names());
        assertFalse(active.contains("read"));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> access.activeToolRegistry(List.of("READ")));
        assertEquals("Missing permission mappings for MCP tools: [READ]", error.getMessage());
    }

    private static McpToolAccessRegistry oneToolRegistry() {
        return McpToolAccessRegistry.of(List.of(
                McpToolAccessRule.of("known", McpToolSurface.GUIDED, List.of("files:read"))));
    }
}
