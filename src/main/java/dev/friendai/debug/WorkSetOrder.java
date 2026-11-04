package dev.friendai.debug;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Position lookup for a large work order, so building a debug work set does not rescan the order per cell. */
public final class WorkSetOrder {
    private WorkSetOrder() {}

    /** The first index of every entry, matching {@link List#indexOf}; later duplicates are ignored. */
    public static <T> Map<T, Integer> firstIndex(List<T> order) {
        if (order == null || order.isEmpty()) {
            return Map.of();
        }
        Map<T, Integer> index = new HashMap<>(Math.max(16, order.size() * 2));
        for (int i = 0; i < order.size(); i++) {
            index.putIfAbsent(order.get(i), i);
        }
        return Map.copyOf(index);
    }
}
