package mcp.gateway.core.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
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
}
