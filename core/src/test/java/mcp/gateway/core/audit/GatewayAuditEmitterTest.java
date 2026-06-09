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
        assertEquals("policy_decision", events.get(0).type());
        assertEquals("client-a", events.get(0).principal());
        assertEquals("deny", events.get(0).outcome());
        assertEquals(Map.of("tool", "demo_tool"), events.get(0).details());
    }

    @Test
    void nullEventsBecomeUnknownFallbackEvents() {
        List<GatewayAuditEvent> events = new ArrayList<>();
        GatewayAuditEmitter emitter = GatewayAuditEmitter.of(events::add);

        emitter.emit(null);

        assertEquals(1, events.size());
        assertNull(events.get(0).type());
        assertNull(events.get(0).principal());
        assertNull(events.get(0).outcome());
        assertEquals(Map.of(), events.get(0).details());
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
