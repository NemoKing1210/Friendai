package dev.friendai.features.storage;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** One observation of a storage container: where it was, what it held and when it was seen. */
public record ContainerRecord(
        StorageKey key, String kind, long lastSeenTick, int slotsUsed, int slotsTotal, Map<String, Integer> counts) {
    public ContainerRecord {
        if (key == null) {
            throw new IllegalArgumentException("key is required");
        }
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("kind is required");
        }
        if (lastSeenTick < 0) {
            throw new IllegalArgumentException("lastSeenTick must be non-negative");
        }
        if (slotsUsed < 0 || slotsTotal < 1 || slotsUsed > slotsTotal) {
            throw new IllegalArgumentException("slots must satisfy 0 <= used <= total and total >= 1");
        }
        if (counts == null) {
            throw new IllegalArgumentException("counts is required");
        }
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                throw new IllegalArgumentException("item id is required");
            }
            if (entry.getValue() == null || entry.getValue() <= 0) {
                throw new IllegalArgumentException("item counts must be positive");
            }
        }
        Map<String, Integer> sorted = new LinkedHashMap<>();
        counts.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                        .reversed()
                        .thenComparing(Map.Entry::getKey))
                .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        counts = Collections.unmodifiableMap(sorted);
    }

    /** How many of the requested items this container held, summed over matching ids. */
    public int countOf(Set<String> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (String id : itemIds) {
            total += counts.getOrDefault(id, 0);
        }
        return total;
    }

    public int freeSlots() {
        return slotsTotal - slotsUsed;
    }
}
