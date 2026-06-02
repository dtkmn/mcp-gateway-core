package mcp.gateway.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class McpToolDescriptorTest {

    @Test
    void normalizesSurfaceAndCapabilities() {
        McpToolDescriptor descriptor = McpToolDescriptor.builder("demo_tool", McpToolSurface.of(" Guided "))
                .capability("Tool.Execute")
                .capability(McpToolCapability.of("audit:write"))
                .build();

        assertEquals("demo_tool", descriptor.name());
        assertEquals(McpToolSurface.GUIDED, descriptor.surface());
        assertTrue(descriptor.hasCapability("tool.execute"));
        assertTrue(descriptor.hasCapability("audit:write"));
    }

    @Test
    void rejectsInvalidIdentity() {
        assertThrows(IllegalArgumentException.class, () -> McpToolDescriptor.builder(" ", McpToolSurface.GUIDED).build());
        assertThrows(IllegalArgumentException.class, () -> McpToolSurface.of("guided/special"));
        assertThrows(IllegalArgumentException.class, () -> McpToolCapability.of("bad capability"));
    }

    @Test
    void capabilitySetIsImmutable() {
        McpToolDescriptor descriptor = new McpToolDescriptor(
                "demo_tool",
                McpToolSurface.EXPERT,
                Set.of(McpToolCapability.of("tool.read"))
        );

        assertThrows(UnsupportedOperationException.class,
                () -> descriptor.capabilities().add(McpToolCapability.of("tool.write")));
    }
}
