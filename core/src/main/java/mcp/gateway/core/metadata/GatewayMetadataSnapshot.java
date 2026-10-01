package mcp.gateway.core.metadata;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Explicit construction-time snapshots for supported gateway metadata values.
 * <p>
 * Existing audit and policy constructors copy only their outer details map.
 * Pass the result of {@link #copyOf(Map)} to those constructors when nested
 * metadata must be independent of caller-owned containers.
 */
public final class GatewayMetadataSnapshot {
    private static final int MAX_CONTAINER_LEVELS = 32;
    private static final int MAX_VALUES = 10_000;

    private GatewayMetadataSnapshot() {
    }

    /**
     * Copies supported metadata into recursively unmodifiable containers.
     * <p>
     * Supported leaves are strings, booleans, characters, boxed primitive
     * numbers, and exact {@link BigInteger}/{@link BigDecimal} values. Maps must
     * have non-null string keys; lists and sets are also copied recursively.
     * Encounter order and key spelling are preserved, but concrete collection
     * classes are not. Arrays and other object types are rejected, without
     * serialization or conversion to strings.
     * <p>
     * A null input becomes an empty map. Root entries with null keys or values
     * are dropped, as in existing audit and policy constructors. Nested null
     * values and collection elements are preserved; nested null keys are
     * rejected. Cycles are rejected by identity, while repeated references
     * outside the active traversal path are accepted and copied again.
     * <p>
     * At most 32 container levels, including the root, and 10,000 retained
     * value visits are accepted. Visits include containers and nested nulls,
     * but not map keys or dropped root entries. Repeated references count on
     * each visit. These limits do not bound byte size or validate JSON values.
     * Callers must not mutate the input during copying.
     *
     * @param details metadata to snapshot, or null
     * @return a recursively unmodifiable snapshot of supported metadata
     * @throws IllegalArgumentException for unsupported values or map keys,
     *         cycles, or exceeded traversal limits; messages omit input contents
     */
    public static Map<String, Object> copyOf(Map<String, ?> details) {
        if (details == null) {
            return Map.of();
        }
        return new Copier().copyRoot(details);
    }

    private static boolean scalar(Object value) {
        return value instanceof String || value instanceof Boolean || value instanceof Character
                || value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof Float || value instanceof Double
                || value.getClass() == BigInteger.class || value.getClass() == BigDecimal.class;
    }

    private static final class Copier {
        private final Set<Object> active = Collections.newSetFromMap(new IdentityHashMap<>());
        private int visits;

        private Map<String, Object> copyRoot(Map<String, ?> details) {
            visit();
            enter(details, 0);
            try {
                return copyMap(details, 1, true);
            } finally {
                active.remove(details);
            }
        }

        private Object copyValue(Object value, int enclosingLevels) {
            visit();
            if (value == null || scalar(value)) {
                return value;
            }
            if (!(value instanceof Map<?, ?> || value instanceof List<?> || value instanceof Set<?>)) {
                throw new IllegalArgumentException("Metadata snapshot contains an unsupported value type");
            }
            enter(value, enclosingLevels);
            try {
                if (value instanceof Map<?, ?> map) {
                    return copyMap(map, enclosingLevels + 1, false);
                }
                if (value instanceof List<?> list) {
                    List<Object> copy = new ArrayList<>();
                    for (Object element : list) {
                        copy.add(copyValue(element, enclosingLevels + 1));
                    }
                    return Collections.unmodifiableList(copy);
                }
                Set<Object> copy = new LinkedHashSet<>();
                for (Object element : (Set<?>) value) {
                    copy.add(copyValue(element, enclosingLevels + 1));
                }
                return Collections.unmodifiableSet(copy);
            } finally {
                active.remove(value);
            }
        }

        private Map<String, Object> copyMap(Map<?, ?> map, int levels, boolean root) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object key = entry.getKey();
                Object value = entry.getValue();
                if (root && (key == null || value == null)) {
                    continue;
                }
                if (!(key instanceof String name)) {
                    throw new IllegalArgumentException("Metadata snapshot maps require non-null string keys");
                }
                copy.put(name, copyValue(value, levels));
            }
            return Collections.unmodifiableMap(copy);
        }

        private void visit() {
            if (++visits > MAX_VALUES) {
                throw new IllegalArgumentException("Metadata snapshot exceeds 10000 values");
            }
        }

        private void enter(Object value, int enclosingLevels) {
            if (enclosingLevels >= MAX_CONTAINER_LEVELS) {
                throw new IllegalArgumentException("Metadata snapshot exceeds 32 container levels");
            }
            if (!active.add(value)) {
                throw new IllegalArgumentException("Metadata snapshot contains a cycle");
            }
        }
    }
}
