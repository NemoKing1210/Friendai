package dev.friendai.agent.verify;

import java.util.Map;

/** The few world facts an outcome is judged by, read on the server thread before and after a task. */
public record WorldSnapshot(
        Map<String, Integer> items,
        int emptySlots,
        int ownerDistance,
        String dimension,
        int healthPercent,
        int foodLevel) {
    public WorldSnapshot {
        items = Map.copyOf(items);
        dimension = dimension == null ? "" : dimension;
        healthPercent = Math.max(0, Math.min(100, healthPercent));
    }

    /** A snapshot without the body facts, for callers that only look at the world. */
    public WorldSnapshot(Map<String, Integer> items, int emptySlots, int ownerDistance, String dimension) {
        this(items, emptySlots, ownerDistance, dimension, 100, -1);
    }
}
