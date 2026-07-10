package mcp.gateway.core.policybundle;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Day/time selector for policy bundle evaluation.
 *
 * @param days days on which the window starts
 * @param start inclusive start time
 * @param end exclusive end time
 */
public record PolicyBundleTimeWindow(Set<DayOfWeek> days,
                                     LocalTime start,
                                     LocalTime end) {

    /**
     * Creates a time window.
     */
    public PolicyBundleTimeWindow {
        if (days == null || days.isEmpty()) {
            throw new IllegalArgumentException("time window days must not be empty");
        }
        LinkedHashSet<DayOfWeek> copy = new LinkedHashSet<>();
        for (DayOfWeek day : days) {
            copy.add(Objects.requireNonNull(day, "day must not be null"));
        }
        days = Set.copyOf(copy);
        start = Objects.requireNonNull(start, "start must not be null");
        end = Objects.requireNonNull(end, "end must not be null");
        if (start.equals(end)) {
            throw new IllegalArgumentException("time window start and end must differ");
        }
    }

    /**
     * Creates a time window.
     *
     * @param days matching days
     * @param start inclusive start time
     * @param end exclusive end time
     * @return time window
     */
    public static PolicyBundleTimeWindow of(Collection<DayOfWeek> days, LocalTime start, LocalTime end) {
        return new PolicyBundleTimeWindow(Set.copyOf(days == null ? List.of() : days), start, end);
    }

    /**
     * Checks whether the supplied bundle-local time falls inside this window.
     *
     * @param bundleTime bundle-local time
     * @return true when matched
     */
    public boolean matches(ZonedDateTime bundleTime) {
        Objects.requireNonNull(bundleTime, "bundleTime must not be null");
        LocalTime currentTime = bundleTime.toLocalTime();
        if (start.isBefore(end)) {
            return days.contains(bundleTime.getDayOfWeek())
                    && !currentTime.isBefore(start)
                    && currentTime.isBefore(end);
        }
        if (!currentTime.isBefore(start)) {
            return days.contains(bundleTime.getDayOfWeek());
        }
        return currentTime.isBefore(end)
                && days.contains(bundleTime.minusDays(1L).getDayOfWeek());
    }
}
