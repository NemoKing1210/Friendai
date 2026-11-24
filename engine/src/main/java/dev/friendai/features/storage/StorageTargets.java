package dev.friendai.features.storage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Pure choice policy over observed containers: which recorded container to walk to for a take, a store or a
 * deposit. Fresh records win over stale ones, then distance; a stale record is still a usable hint because the
 * task revalidates the container on arrival.
 */
public final class StorageTargets {
    private StorageTargets() {}

    public static Optional<ContainerRecord> take(
            StorageIndex index,
            String dimension,
            int x,
            int y,
            int z,
            Set<String> itemIds,
            int minCount,
            long nowTick,
            StorageLimits limits) {
        if (index == null || itemIds == null || itemIds.isEmpty()) {
            return Optional.empty();
        }
        int needed = Math.max(1, minCount);
        List<ContainerRecord> eligible = new ArrayList<>();
        for (ContainerRecord record : index.records()) {
            if (record.key().dimension().equals(dimension) && record.countOf(itemIds) >= needed) {
                eligible.add(record);
            }
        }
        eligible.sort(Comparator.comparing((ContainerRecord record) -> stale(record, nowTick, limits))
                .thenComparingLong(record -> distanceSq(record, x, y, z))
                .thenComparing(Comparator.comparingInt((ContainerRecord record) -> record.countOf(itemIds))
                        .reversed())
                .thenComparing(StorageTargets::compareKeys));
        return eligible.stream().findFirst();
    }

    /**
     * Store order within {@code radius}: a container with a counted empty slot first, then a counted-full one that
     * already holds one of the carried items (it may still take the stack through a partial stack of the same item and
     * components), then a record whose capacity was never counted. A counted-full container with none of the carried
     * items is not eligible. Freshness, distance and free slots order the candidates inside a group.
     */
    public static List<ContainerRecord> storeOrder(
            StorageIndex index,
            String dimension,
            int x,
            int y,
            int z,
            Set<String> itemIds,
            long nowTick,
            StorageLimits limits,
            int radius) {
        if (index == null) {
            return List.of();
        }
        Set<String> carried = itemIds == null ? Set.of() : Set.copyOf(itemIds);
        long scope = radius <= 0 ? Long.MAX_VALUE : (long) radius * radius;
        List<ContainerRecord> eligible = new ArrayList<>();
        for (ContainerRecord record : index.records()) {
            if (!record.key().dimension().equals(dimension) || distanceSq(record, x, y, z) > scope) {
                continue;
            }
            if (storeGroup(record, carried) < NOT_A_STORE_TARGET) {
                eligible.add(record);
            }
        }
        eligible.sort(Comparator.comparingInt((ContainerRecord record) -> storeGroup(record, carried))
                .thenComparing(record -> stale(record, nowTick, limits))
                .thenComparingLong(record -> distanceSq(record, x, y, z))
                .thenComparing(
                        Comparator.comparingInt(ContainerRecord::freeSlots).reversed())
                .thenComparing(StorageTargets::compareKeys));
        return List.copyOf(eligible);
    }

    /** Group 3 is "not a store target": a counted-full container that holds none of the carried items. */
    private static final int NOT_A_STORE_TARGET = 3;

    private static int storeGroup(ContainerRecord record, Set<String> carried) {
        if (!record.capacityKnown()) {
            return 2;
        }
        if (record.freeSlots() > 0) {
            return 0;
        }
        return record.countOf(carried) > 0 ? 1 : NOT_A_STORE_TARGET;
    }

    /** Orders query rows for display: same dimension first, then freshness, distance and count. */
    public static List<StoredCandidate> ordered(
            Collection<StoredCandidate> candidates, String dimension, int x, int y, int z) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<StoredCandidate> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparing((StoredCandidate candidate) ->
                        !candidate.record().key().dimension().equals(dimension))
                .thenComparing(StoredCandidate::stale)
                .thenComparingLong(candidate -> distanceSq(candidate.record(), x, y, z))
                .thenComparing(Comparator.comparingInt(StoredCandidate::count).reversed())
                .thenComparing(candidate -> candidate.record().key().dimension())
                .thenComparingInt(candidate -> candidate.record().key().x())
                .thenComparingInt(candidate -> candidate.record().key().y())
                .thenComparingInt(candidate -> candidate.record().key().z()));
        return List.copyOf(sorted);
    }

    /**
     * Deposit order: containers that last held the item, then counted free slots, then records whose capacity was
     * never counted, then counted-full containers; distance breaks ties inside each group.
     */
    public static List<ContainerRecord> depositOrder(
            Collection<ContainerRecord> records, String dimension, String itemId, int x, int y, int z) {
        return depositOrder(records, dimension, Set.of(itemId), x, y, z);
    }

    public static List<ContainerRecord> depositOrder(
            Collection<ContainerRecord> records, String dimension, Set<String> itemIds, int x, int y, int z) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        Set<String> item = Set.copyOf(itemIds);
        List<ContainerRecord> sorted = new ArrayList<>(records);
        sorted.sort(Comparator.comparingInt((ContainerRecord record) -> depositGroup(record, item))
                .thenComparing(record -> !record.key().dimension().equals(dimension))
                .thenComparingLong(record -> distanceSq(record, x, y, z))
                .thenComparing(StorageTargets::compareKeys));
        return List.copyOf(sorted);
    }

    private static int depositGroup(ContainerRecord record, Set<String> item) {
        if (record.countOf(item) > 0) {
            return 0;
        }
        if (!record.capacityKnown()) {
            return 2;
        }
        return record.freeSlots() > 0 ? 1 : 3;
    }

    private static boolean stale(ContainerRecord record, long nowTick, StorageLimits limits) {
        long age = Math.max(0, nowTick - record.lastSeenTick());
        return age > limits.staleAfterTicks();
    }

    private static long distanceSq(ContainerRecord record, int x, int y, int z) {
        long dx = (long) record.key().x() - x;
        long dy = (long) record.key().y() - y;
        long dz = (long) record.key().z() - z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static int compareKeys(ContainerRecord left, ContainerRecord right) {
        int compared = left.key().dimension().compareTo(right.key().dimension());
        if (compared != 0) {
            return compared;
        }
        compared = Integer.compare(left.key().x(), right.key().x());
        if (compared != 0) {
            return compared;
        }
        compared = Integer.compare(left.key().y(), right.key().y());
        if (compared != 0) {
            return compared;
        }
        return Integer.compare(left.key().z(), right.key().z());
    }
}
