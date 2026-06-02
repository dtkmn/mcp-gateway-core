package mcp.gateway.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class McpToolRegistryTest {

    @Test
    void indexesToolsByNameSurfaceAndCapability() {
        McpToolDescriptor guided = McpToolDescriptor.builder("demo_guided_tool", McpToolSurface.GUIDED)
                .capability("tool.execute")
                .build();
        McpToolDescriptor expert = McpToolDescriptor.builder("demo_expert_tool", McpToolSurface.EXPERT)
                .capability("tool.inspect")
                .build();

        McpToolRegistry registry = McpToolRegistry.of(List.of(guided, expert));

        assertTrue(registry.contains("demo_guided_tool"));
        assertTrue(registry.hasCapability("demo_guided_tool", "tool.execute"));
        assertFalse(registry.hasCapability("demo_guided_tool", "tool.inspect"));
        assertEquals(List.of(guided), registry.descriptorsForSurface(McpToolSurface.GUIDED));
        assertEquals(Set.of("demo_expert_tool"), registry.namesWithCapability(McpToolCapability.of("tool.inspect")));
    }

    @Test
    void rejectsDuplicateToolNames() {
        McpToolDescriptor first = McpToolDescriptor.builder("demo_tool", McpToolSurface.GUIDED).build();
        McpToolDescriptor second = McpToolDescriptor.builder("demo_tool", McpToolSurface.EXPERT).build();

        assertThrows(IllegalArgumentException.class, () -> McpToolRegistry.of(List.of(first, second)));
    }

    @Test
    void requiresKnownDescriptor() {
        McpToolRegistry registry = McpToolRegistry.of(List.of());

        assertThrows(IllegalArgumentException.class, () -> registry.requireDescriptor("missing_tool"));
    }
}
