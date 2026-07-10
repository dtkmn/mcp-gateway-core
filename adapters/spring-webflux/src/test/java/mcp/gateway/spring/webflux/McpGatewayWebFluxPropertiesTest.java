package mcp.gateway.spring.webflux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class McpGatewayWebFluxPropertiesTest {

    @Test
    void normalizesSupportedEndpointAndBodyLimitValues() {
        McpGatewayWebFluxProperties properties = new McpGatewayWebFluxProperties("  api/mcp  ", 12, 7);

        assertEquals("/api/mcp", properties.mcpEndpoint());
        assertEquals(1024, properties.maxBodyBytes());
        assertEquals(7, properties.governanceFilterOrder());
        assertEquals(McpGatewayWebFluxProperties.DEFAULT_MCP_ENDPOINT,
                new McpGatewayWebFluxProperties("  ", 4096, 0).mcpEndpoint());
    }

    @Test
    void rejectsEndpointValuesThatCannotBeMatchedAsOneApplicationPath() {
        assertInvalidEndpoint("/mcp?transport=sse");
        assertInvalidEndpoint("/mcp#fragment");
        assertInvalidEndpoint("/mcp;version=1");
        assertInvalidEndpoint("//mcp");
        assertInvalidEndpoint("/mcp path");
        assertInvalidEndpoint("/mcp%20path");
        assertInvalidEndpoint("/mcp%C2%A0path");
        assertInvalidEndpoint("/mcp%0Apath");
        assertInvalidEndpoint("/mcp%zz");
    }

    private static void assertInvalidEndpoint(String endpoint) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new McpGatewayWebFluxProperties(endpoint, 4096, 0)
        );

        assertEquals(
                "mcpEndpoint must be a path without query, fragment, matrix parameters, or whitespace",
                failure.getMessage()
        );
    }
}
