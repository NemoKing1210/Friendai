package dev.friendai.knowledge;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/**
 * A bounded, age-aware set of direct observations. Re-observing the same location replaces the old entry and moves it
 * to the newest end; an entry older than {@code maxAgeTicks} is no longer returned. The window stores observations as
 * they were taken and never changes their contents.
 */
public final class ObservationWindow {
    private final int capacity;
    private final long maxAgeTicks;
    private final LinkedHashMap<ResourceObservation.Location, ResourceObservation> entries = new LinkedHashMap<>();
    private long newestTick = Long.MIN_VALUE;

    public ObservationWindow(int capacity, long maxAgeTicks) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive: " + capacity);
        if (maxAgeTicks < 0) throw new IllegalArgumentException("maxAgeTicks must not be negative: " + maxAgeTicks);
        this.capacity = capacity;
        this.maxAgeTicks = maxAgeTicks;
    }

    /** Records one observation; returns false when the observation is already outside the window. */
    public boolean record(ResourceObservation observation) {
        Objects.requireNonNull(observation, "observation");
        long tick = observation.observedTick();
        newestTick = Math.max(newestTick, tick);
        entries.remove(observation.location());
        entries.put(observation.location(), observation);
        purge(newestTick);
        while (entries.size() > capacity) {
            entries.remove(entries.keySet().iterator().next());
        }
        return entries.containsValue(observation);
    }

    /** Observations no older than {@code maxAgeTicks} at {@code now}, in insertion order. */
    public List<ResourceObservation> recent(long now) {
        purge(now);
        return List.copyOf(entries.values());
    }

    public boolean forget(ResourceObservation.Location location) {
        return entries.remove(location) != null;
    }

    public void clear() {
        entries.clear();
        newestTick = Long.MIN_VALUE;
    }

    public int size() {
        return entries.size();
    }

    private void purge(long now) {
        entries.values()
                .removeIf(observation ->
                        observation.observedTick() > now || now - observation.observedTick() > maxAgeTicks);
    }
}
