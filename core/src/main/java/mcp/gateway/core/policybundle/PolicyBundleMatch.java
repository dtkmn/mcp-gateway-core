package mcp.gateway.core.policybundle;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Tool, host, and time-window selectors for one policy rule.
 *
 * @param tools exact, case-sensitive MCP tool names
 * @param hosts exact or wildcard host patterns
 * @param timeWindows allowed time windows
 */
public record PolicyBundleMatch(List<String> tools,
                                List<String> hosts,
                                List<PolicyBundleTimeWindow> timeWindows) {

    /**
     * Creates a normalized selector set.
     */
    public PolicyBundleMatch {
        tools = normalizeTools(tools);
        hosts = normalizeHosts(hosts);
        timeWindows = List.copyOf(timeWindows == null ? List.of() : timeWindows);
        timeWindows.forEach(window -> Objects.requireNonNull(window, "time window must not be null"));
        if (tools.isEmpty() && hosts.isEmpty() && timeWindows.isEmpty()) {
            throw new IllegalArgumentException("policy bundle match must include at least one selector");
        }
    }

    /**
     * Creates a selector set.
     *
     * @param tools exact tool names
     * @param hosts host patterns
     * @param timeWindows time windows
     * @return match
     */
    public static PolicyBundleMatch of(Collection<String> tools,
                                       Collection<String> hosts,
                                       Collection<PolicyBundleTimeWindow> timeWindows) {
        return new PolicyBundleMatch(
                tools == null ? List.of() : new java.util.ArrayList<>(tools),
                hosts == null ? List.of() : new java.util.ArrayList<>(hosts),
                timeWindows == null ? List.of() : List.copyOf(timeWindows)
        );
    }

    private static List<String> normalizeTools(Collection<String> values) {
        return normalizeStrings(values, false);
    }

    private static List<String> normalizeHosts(Collection<String> values) {
        return normalizeStrings(values, true);
    }

    private static List<String> normalizeStrings(Collection<String> values, boolean lowerCase) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("policy bundle match selector entries must not be blank");
            }
            String selector = value.trim();
            normalized.add(lowerCase ? selector.toLowerCase(Locale.ROOT) : selector);
        }
        return List.copyOf(normalized);
    }
}
