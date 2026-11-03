package dev.friendai.features.storage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A bounded, per-owner index of observed storage containers. It is not a world scan: only
 * {@link #observe} adds a record, so an unopened container is unknown and is never treated as empty.
 *
 * <p>Eviction is by insertion order after a record is refreshed, so the oldest observation leaves first. The
 * index has no I/O and no Minecraft type; a server adapter supplies observations and the codec persists them.
 */
public final class StorageIndex {
    private final StorageLimits limits;
    private final LinkedHashMap<StorageKey, ContainerRecord> records = new LinkedHashMap<>();
    private long generation;

    public StorageIndex() {
        this(StorageLimits.DEFAULTS);
    }

    public StorageIndex(StorageLimits limits) {
        if (limits == null) {
            throw new IllegalArgumentException("limits is required");
        }
        this.limits = limits;
    }

    public StorageLimits limits() {
        return limits;
    }

    /** Records or refreshes one observation. The newest observation moves to the back. */
    public void observe(ContainerRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("record is required");
        }
        ContainerRecord bounded = bounded(record);
        records.remove(bounded.key());
        records.put(bounded.key(), bounded);
        while (records.size() > limits.maxContainers()) {
            var oldest = records.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
        generation++;
    }

    public void forget(StorageKey key) {
        if (key != null && records.remove(key) != null) {
            generation++;
        }
    }

    public Optional<ContainerRecord> record(StorageKey key) {
        return key == null ? Optional.empty() : Optional.ofNullable(records.get(key));
    }

    public Collection<ContainerRecord> records() {
        return List.copyOf(records.values());
    }

    /**
     * Matches records holding at least {@code max(1, minCount)} of the requested ids. Fresh records come first,
     * then age, count descending and the key for determinism; stale records are included but marked.
     */
    public List<StoredCandidate> find(Set<String> itemIds, int minCount, long nowTick) {
        if (itemIds == null || itemIds.isEmpty()) {
            return List.of();
        }
        int needed = Math.max(1, minCount);
        List<StoredCandidate> matches = new ArrayList<>();
        for (ContainerRecord record : records.values()) {
            int count = record.countOf(itemIds);
            if (count < needed) {
                continue;
            }
            long age = Math.max(0, nowTick - record.lastSeenTick());
            matches.add(new StoredCandidate(record, count, age, age > limits.staleAfterTicks()));
        }
        matches.sort(Comparator.comparing(StoredCandidate::stale)
                .thenComparingLong(StoredCandidate::ageTicks)
                .thenComparing(Comparator.comparingInt(StoredCandidate::count).reversed())
                .thenComparing(candidate -> candidate.record().key().dimension())
                .thenComparingInt(candidate -> candidate.record().key().x())
                .thenComparingInt(candidate -> candidate.record().key().y())
                .thenComparingInt(candidate -> candidate.record().key().z()));
        return List.copyOf(matches);
    }

    public int total(Set<String> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (ContainerRecord record : records.values()) {
            total += record.countOf(itemIds);
        }
        return total;
    }

    public int size() {
        return records.size();
    }

    public long generation() {
        return generation;
    }

    /** Restores the generation a codec read from disk; it is not a mutation. */
    public void setGeneration(long generation) {
        if (generation < 0) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
        this.generation = generation;
    }

    private ContainerRecord bounded(ContainerRecord record) {
        Map<String, Integer> counts = record.counts();
        if (counts.size() <= limits.maxKindsPerContainer()) {
            return record;
        }
        Map<String, Integer> kept = new LinkedHashMap<>();
        int taken = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (taken++ == limits.maxKindsPerContainer()) {
                break;
            }
            kept.put(entry.getKey(), entry.getValue());
        }
        return new ContainerRecord(
                record.key(), record.kind(), record.lastSeenTick(), record.slotsUsed(), record.slotsTotal(), kept);
    }
}
