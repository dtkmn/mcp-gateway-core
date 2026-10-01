package mcp.gateway.core.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mcp.gateway.core.metadata.GatewayMetadataSnapshot;
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

    @Test
    void retainingSinkKeepsExplicitSnapshotStableAgainstSourceAndConsumerMutations() {
        Map<String, Object> trace = new LinkedHashMap<>(Map.of("outcome", "allow"));
        List<Object> traces = new ArrayList<>(List.of(trace));
        Map<String, Object> source = new LinkedHashMap<>(Map.of("trace", traces));
        List<GatewayAuditEvent> events = new ArrayList<>();
        GatewayAuditEmitter emitter = GatewayAuditEmitter.of(events::add);
        GatewayAuditEvent event = GatewayAuditEvent.of("policy_decision", "client-a", "allow",
                GatewayMetadataSnapshot.copyOf(source));

        emitter.emit(event);
        trace.put("outcome", "deny");
        traces.clear();
        source.clear();

        Map<String, Object> retained = events.get(0).details();
        assertEquals(Map.of("trace", List.of(Map.of("outcome", "allow"))), retained);
        List<?> retainedTraces = (List<?>) retained.get("trace");
        Map<?, ?> retainedTrace = (Map<?, ?>) retainedTraces.get(0);
        assertThrows(UnsupportedOperationException.class, retained::clear);
        assertThrows(UnsupportedOperationException.class, retainedTraces::clear);
        assertThrows(UnsupportedOperationException.class, retainedTrace::clear);
        assertEquals(Map.of("trace", List.of(Map.of("outcome", "allow"))), events.get(0).details());
    }
}
