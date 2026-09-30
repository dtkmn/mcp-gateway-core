package mcp.gateway.core.metadata;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class GatewayMetadataSnapshotTest {

    @Test
    void preservesSupportedScalarTypesAndKeySpelling() {
        List<Object> values = List.of(" text ", true, 'x', (byte) 1, (short) 2, 3, 4L,
                5.5F, 6.5D, new BigInteger("12345678901234567890"), new BigDecimal("1.2300"));
        Map<String, Object> source = new LinkedHashMap<>();
        for (int i = 0; i < values.size(); i++) {
            source.put(" key " + i + " ", values.get(i));
        }

        Map<String, Object> snapshot = GatewayMetadataSnapshot.copyOf(source);

        assertEquals(new ArrayList<>(source.keySet()), new ArrayList<>(snapshot.keySet()));
        assertEquals(values, new ArrayList<>(snapshot.values()));
        assertEquals(values.stream().map(Object::getClass).toList(),
                snapshot.values().stream().map(Object::getClass).toList());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.put("new", "value"));
    }

    @Test
    void emptyRootsAndSkippedRootEntriesDoNotTraverseTheirValues() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put(null, source);
        source.put("dropped", null);
        source.put("kept", "value");

        assertEquals(Map.of(), GatewayMetadataSnapshot.copyOf(null));
        assertEquals(Map.of(), GatewayMetadataSnapshot.copyOf(Map.of()));
        assertEquals(Map.of("kept", "value"), GatewayMetadataSnapshot.copyOf(source));

        source.put(null, new Object());
        assertEquals(Map.of("kept", "value"), GatewayMetadataSnapshot.copyOf(source));
    }

    @Test
    void recursivelySnapshotsMapsListsAndSetsIncludingNestedNullsAndOrder() {
        List<Object> sourceList = new ArrayList<>(Arrays.asList("first", null, "last"));
        Map<String, Object> sourceMap = new LinkedHashMap<>();
        sourceMap.put(" values ", sourceList);
        sourceMap.put("nullValue", null);
        Map<String, Object> setChild = new LinkedHashMap<>(Map.of("value", "before"));
        Set<Object> sourceSet = new LinkedHashSet<>(Arrays.asList("beta", null, "alpha", setChild));
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("nested", sourceMap);
        source.put("members", sourceSet);

        Map<String, Object> snapshot = GatewayMetadataSnapshot.copyOf(source);
        sourceList.clear();
        sourceMap.clear();
        sourceSet.clear();
        setChild.clear();
        source.clear();

        Map<?, ?> nested = (Map<?, ?>) snapshot.get("nested");
        List<?> values = (List<?>) nested.get(" values ");
        Set<?> members = (Set<?>) snapshot.get("members");
        assertEquals(List.of("nested", "members"), new ArrayList<>(snapshot.keySet()));
        assertEquals(List.of(" values ", "nullValue"), new ArrayList<>(nested.keySet()));
        assertEquals(Arrays.asList("first", null, "last"), values);
        assertNull(nested.get("nullValue"));
        assertEquals(Arrays.asList("beta", null, "alpha", Map.of("value", "before")), new ArrayList<>(members));
        assertThrows(UnsupportedOperationException.class, nested::clear);
        assertThrows(UnsupportedOperationException.class, values::clear);
        assertThrows(UnsupportedOperationException.class, members::clear);
        Map<?, ?> retainedSetChild = (Map<?, ?>) new ArrayList<>(members).get(3);
        assertThrows(UnsupportedOperationException.class, retainedSetChild::clear);
    }

    @Test
    void rejectsUnsupportedValuesWithoutRenderingTheirContents() {
        Object opaque = new Object() {
            @Override
            public String toString() {
                throw new AssertionError("Unsupported values must not be rendered");
            }
        };
        List<Object> unsupported = List.of(new int[] {1}, new String[] {"secret-value"},
                opaque, new ArrayDeque<>(List.of("secret-value")), new AtomicInteger(123),
                new BigInteger("123") { }, new BigDecimal("123") { });
        for (Object value : unsupported) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> GatewayMetadataSnapshot.copyOf(Map.of("secret-key", List.of(value))));
            assertEquals("Metadata snapshot contains an unsupported value type", error.getMessage());
        }
    }

    @Test
    void rejectsNullAndNonStringNestedMapKeysWithoutRenderingThem() {
        Object opaqueKey = new Object() {
            @Override
            public String toString() {
                throw new AssertionError("Invalid keys must not be rendered");
            }
        };
        for (Object key : Arrays.asList(null, 42, opaqueKey)) {
            Map<Object, Object> nested = new LinkedHashMap<>();
            nested.put(key, "secret-value");
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> GatewayMetadataSnapshot.copyOf(Map.of("secret-key", nested)));
            assertEquals("Metadata snapshot maps require non-null string keys", error.getMessage());
        }
    }

    @Test
    void rejectsDirectAndIndirectContainerCycles() {
        Map<String, Object> mapCycle = new LinkedHashMap<>();
        mapCycle.put("self", mapCycle);
        List<Object> listCycle = new ArrayList<>();
        listCycle.add(listCycle);
        Set<Object> setCycle = new LinkedHashSet<>();
        setCycle.add(setCycle);
        Map<String, Object> indirectCycle = new LinkedHashMap<>();
        indirectCycle.put("list", List.of(indirectCycle));

        for (Map<String, ?> root : List.of(mapCycle, Map.of("list", listCycle),
                Map.of("set", setCycle), indirectCycle)) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> GatewayMetadataSnapshot.copyOf(root));
            assertEquals("Metadata snapshot contains a cycle", error.getMessage());
        }
    }

    @Test
    void sharedAcyclicContainersAreAllowedAndTheirCopiesDoNotShareSourceState() {
        List<Object> shared = new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("value", "before"))));
        Map<String, Object> snapshot = GatewayMetadataSnapshot.copyOf(Map.of("left", shared, "right", shared));

        shared.clear();

        assertEquals(List.of(Map.of("value", "before")), snapshot.get("left"));
        assertEquals(snapshot.get("left"), snapshot.get("right"));
        Map<?, ?> retained = (Map<?, ?>) ((List<?>) snapshot.get("left")).get(0);
        assertThrows(UnsupportedOperationException.class, retained::clear);
    }

    @Test
    void depthLimitCountsTheRootAndEveryContainerType() {
        assertDoesNotThrow(() -> GatewayMetadataSnapshot.copyOf(atDepth(32)));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> GatewayMetadataSnapshot.copyOf(atDepth(33)));
        assertEquals("Metadata snapshot exceeds 32 container levels", error.getMessage());
    }

    @Test
    void valueBudgetCountsRootNestedContainersAndNestedNullsButNotKeys() {
        assertDoesNotThrow(() -> GatewayMetadataSnapshot.copyOf(
                Map.of("values", Collections.nCopies(9_998, null))));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> GatewayMetadataSnapshot.copyOf(
                Map.of("values", Collections.nCopies(9_999, null))));
        assertEquals("Metadata snapshot exceeds 10000 values", error.getMessage());

        Map<String, Object> flat = new LinkedHashMap<>();
        for (int i = 0; i < 9_999; i++) {
            flat.put("key-" + i, "value");
        }
        flat.put(null, flat);
        flat.put("ignored", null);
        assertEquals(9_999, GatewayMetadataSnapshot.copyOf(flat).size());
        flat.put("one-too-many", "value");
        assertThrows(IllegalArgumentException.class, () -> GatewayMetadataSnapshot.copyOf(flat));
    }

    @Test
    void repeatedReferencesCountForEachVisitRatherThanByContainerIdentity() {
        List<?> withinBudget = Collections.nCopies(4_998, null);
        List<?> overBudget = Collections.nCopies(4_999, null);

        assertDoesNotThrow(() -> GatewayMetadataSnapshot.copyOf(Map.of("left", withinBudget, "right", withinBudget)));
        assertThrows(IllegalArgumentException.class,
                () -> GatewayMetadataSnapshot.copyOf(Map.of("left", overBudget, "right", overBudget)));
    }

    private static Map<String, Object> atDepth(int containerLevels) {
        Object nested = "leaf";
        for (int level = 1; level < containerLevels; level++) {
            nested = switch (level % 3) {
                case 0 -> Map.of("child", nested);
                case 1 -> List.of(nested);
                default -> new LinkedHashSet<>(List.of(nested));
            };
        }
        return Map.of("root", nested);
    }
}
