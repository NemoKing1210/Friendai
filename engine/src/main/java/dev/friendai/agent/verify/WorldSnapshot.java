package dev.friendai.agent.verify;

import java.util.Map;

/** The few world facts an outcome is judged by, read on the server thread before and after a task. */
public record WorldSnapshot(Map<String, Integer> items, int emptySlots, int ownerDistance, String dimension) {
    public WorldSnapshot {
        items = Map.copyOf(items);
        dimension = dimension == null ? "" : dimension;
    }
}
