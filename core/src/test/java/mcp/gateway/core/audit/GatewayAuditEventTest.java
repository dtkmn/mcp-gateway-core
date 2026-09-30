package mcp.gateway.core.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GatewayAuditEventTest {

    @Test
    void normalizesTextAndDropsNullDetails() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("kept", "value");
        details.put("dropped", null);
        details.put(null, "dropped");

        GatewayAuditEvent event = GatewayAuditEvent.of(" policy_decision ", " client-a ", " allow ", details);

        assertEquals("policy_decision", event.type());
        assertEquals("client-a", event.principal());
        assertEquals("allow", event.outcome());
        assertEquals(Map.of("kept", "value"), event.details());
    }

    @Test
    void detailsAreImmutable() {
        GatewayAuditEvent event = GatewayAuditEvent.of("type", "principal", "outcome", Map.of("key", "value"));

        assertThrows(UnsupportedOperationException.class, () -> event.details().put("another", "value"));
    }

    @Test
    void legacyConstructorAndFactoryKeepOpaqueAndNestedValuesShallow() {
        Object opaque = new Object();
        int[] array = {1};
        List<String> nested = new ArrayList<>(List.of("before"));
        Map<String, Object> source = new LinkedHashMap<>();
        source.put(" opaque ", opaque);
        source.put(null, "dropped");
        source.put("dropped", null);
        source.put("array", array);
        source.put("nested", nested);
        List<GatewayAuditEvent> events = List.of(
                new GatewayAuditEvent("type", "principal", "outcome", source),
                GatewayAuditEvent.of("type", "principal", "outcome", source));

        source.clear();
        nested.add("after");
        array[0] = 2;

        for (GatewayAuditEvent event : events) {
            assertEquals(List.of(" opaque ", "array", "nested"), new ArrayList<>(event.details().keySet()));
            assertSame(opaque, event.details().get(" opaque "));
            assertSame(array, event.details().get("array"));
            assertSame(nested, event.details().get("nested"));
            assertEquals(List.of("before", "after"), event.details().get("nested"));
            assertThrows(UnsupportedOperationException.class, () -> event.details().clear());
        }
    }
}
