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
        eligible.sort(Comparator.comparing(
                        (ContainerRecord record) -> stale(record, nowTick, limits))
                .thenComparingLong(record -> distanceSq(record, x, y, z))
                .thenComparing(Comparator.comparingInt(
                                (ContainerRecord record) -> record.countOf(itemIds))
                        .reversed())
                .thenComparing(StorageTargets::compareKeys));
        return eligible.stream().findFirst();
    }

    public static Optional<ContainerRecord> store(
            StorageIndex index, String dimension, int x, int y, int z, long nowTick, StorageLimits limits) {
        if (index == null) {
            return Optional.empty();
        }
        List<ContainerRecord> eligible = new ArrayList<>();
        for (ContainerRecord record : index.records()) {
            if (record.key().dimension().equals(dimension) && record.freeSlots() > 0) {
                eligible.add(record);
            }
        }
        eligible.sort(Comparator.comparing(
                        (ContainerRecord record) -> stale(record, nowTick, limits))
                .thenComparingLong(record -> distanceSq(record, x, y, z))
                .thenComparing(Comparator.comparingInt(ContainerRecord::freeSlots)
                        .reversed())
                .thenComparing(StorageTargets::compareKeys));
        return eligible.stream().findFirst();
    }

    /** Orders query rows for display: same dimension first, then freshness, distance and count. */
    public static List<StoredCandidate> ordered(
            Collection<StoredCandidate> candidates, String dimension, int x, int y, int z) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<StoredCandidate> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparing(
                        (StoredCandidate candidate) ->
                                !candidate.record().key().dimension().equals(dimension))
                .thenComparing(StoredCandidate::stale)
                .thenComparingLong(candidate -> distanceSq(candidate.record(), x, y, z))
                .thenComparing(Comparator.comparingInt(StoredCandidate::count)
                        .reversed())
                .thenComparing(candidate -> candidate.record().key().dimension())
                .thenComparingInt(candidate -> candidate.record().key().x())
                .thenComparingInt(candidate -> candidate.record().key().y())
                .thenComparingInt(candidate -> candidate.record().key().z()));
        return List.copyOf(sorted);
    }

    /**
     * Deposit order: containers that last held the item, then containers with a free slot, then the rest; distance
     * breaks ties inside each group.
     */
    public static List<ContainerRecord> depositOrder(
            Collection<ContainerRecord> records, String dimension, String itemId, int x, int y, int z) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        Set<String> item = Set.of(itemId);
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
        return record.freeSlots() > 0 ? 1 : 2;
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
