package mcp.gateway.core.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GatewayAuditEmitterTest {

    @Test
    void emitsCreatedEventsToSink() {
        List<GatewayAuditEvent> events = new ArrayList<>();
        GatewayAuditEmitter emitter = GatewayAuditEmitter.of(events::add);

        emitter.emit(" policy_decision ", " client-a ", " deny ", Map.of("tool", "demo_tool"));

        assertEquals(1, events.size());
        assertEquals("policy_decision", events.getFirst().type());
        assertEquals("client-a", events.getFirst().principal());
        assertEquals("deny", events.getFirst().outcome());
        assertEquals(Map.of("tool", "demo_tool"), events.getFirst().details());
    }

    @Test
    void nullEventsBecomeUnknownFallbackEvents() {
        List<GatewayAuditEvent> events = new ArrayList<>();
        GatewayAuditEmitter emitter = GatewayAuditEmitter.of(events::add);

        emitter.emit(null);

        assertEquals(1, events.size());
        assertNull(events.getFirst().type());
        assertNull(events.getFirst().principal());
        assertNull(events.getFirst().outcome());
        assertEquals(Map.of(), events.getFirst().details());
    }

    @Test
    void rejectsMissingSink() {
        assertThrows(NullPointerException.class, () -> GatewayAuditEmitter.of(null));
    }

    @Test
    void noOpSinkAcceptsEvents() {
        GatewayAuditSink.noop().publish("type", "principal", "outcome", Map.of("key", "value"));
    }
}
