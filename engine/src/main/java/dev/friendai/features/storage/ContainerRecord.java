package dev.friendai.features.storage;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * One observation of a storage container: where it was, what it held and when it was seen.
 *
 * <p>{@code slotsTotal == 0} means the opening that recorded this container never counted its slots, as every record
 * written before slot counts were persisted did. Such a record is a hint that the container exists and says nothing
 * about room; only a counted record can claim a free slot.
 */
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
        if (slotsUsed < 0 || slotsTotal < 0 || slotsTotal > 0 && slotsUsed > slotsTotal) {
            throw new IllegalArgumentException(
                    "slots must satisfy used >= 0, total >= 0 and used <= total when total is known");
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

    /** True when this observation counted the container's slots, so {@link #freeSlots()} is a fact. */
    public boolean capacityKnown() {
        return slotsTotal > 0;
    }

    /** Slots the observation counted as empty; 0 when capacity was never counted, never a guess. */
    public int freeSlots() {
        return capacityKnown() ? slotsTotal - slotsUsed : 0;
    }
}
