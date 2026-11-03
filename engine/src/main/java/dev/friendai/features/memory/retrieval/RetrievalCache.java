package dev.friendai.features.memory.retrieval;

import java.util.LinkedHashMap;
import java.util.Optional;

/**
 * A bounded, in-process cache of derived indexes for one companion session. A slot is keyed by a stable SHA-256 digest
 * of the owner, the caller's visibility partition, the covered facts' revisions, the redacted-text digest (which pins
 * the mask the vectors were built under), the backend version and the vector width, so a guest never reuses the owner's
 * index and any add, edit, forget or redaction change moves the key.
 *
 * <p>Every fill is guarded by an epoch. A build that overlaps a forget or an owner switch (which calls
 * {@link #invalidate()}) cannot be published afterwards: {@link #publish} returns false and the caller falls back
 * instead of showing a selection computed against facts that no longer exist. Eviction is oldest-insertion first.
 */
public final class RetrievalCache {
    /** The most principals one session caches at once; a busier server rebuilds instead of growing without bound. */
    public static final int MAX_SCOPES = 8;

    /**
     * The identity of one cached index. Every part is in the digest: the same facts must not serve two principals, and
     * two backends or widths are two different vector spaces even when the text is identical.
     */
    public record Key(String owner, String partition, long facts, long masked, String backend, int dimensions) {
        public Key {
            owner = owner == null ? "" : owner;
            partition = partition == null ? "" : partition;
            backend = backend == null ? "" : backend;
            dimensions = Math.max(0, dimensions);
        }

        /** The stable digest this slot is stored under. */
        public String digest() {
            return RetrievalDigest.key(
                    owner,
                    partition,
                    Long.toString(facts),
                    Long.toString(masked),
                    backend,
                    Integer.toString(dimensions));
        }
    }

    private record Slot(String owner, String partition, long facts, long masked, RetrievalIndex index) {}

    private final LinkedHashMap<String, Slot> slots = new LinkedHashMap<>();
    private long epoch;

    /** The epoch to pass to {@link #publish} after the build started; a later invalidation rejects that publish. */
    public long captureEpoch() {
        return epoch;
    }

    /** The index exactly while its key is current; any fact, principal or backend change is a miss. */
    public Optional<RetrievalIndex> get(Key key) {
        if (key == null) {
            return Optional.empty();
        }
        Slot slot = slots.get(key.digest());
        return slot == null ? Optional.empty() : Optional.of(slot.index());
    }

    /** Stores an index only when nothing invalidated the cache since {@code capturedEpoch} was read. */
    public boolean publish(long capturedEpoch, Key key, RetrievalIndex index) {
        if (key == null || index == null || !index.isUsable() || capturedEpoch != epoch) {
            return false;
        }
        String digest = key.digest();
        slots.remove(digest);
        slots.put(digest, new Slot(key.owner(), key.partition(), key.facts(), key.masked(), index));
        while (slots.size() > MAX_SCOPES) {
            slots.remove(slots.keySet().iterator().next());
        }
        return true;
    }

    /**
     * Drops this principal's stale slots — a forget, an edit or a redaction change moves its facts or mask — so deleted
     * vectors leave memory at once instead of waiting for eviction. Other principals and other owners are untouched:
     * their expected facts are not known here, and dropping them would make alternating turns rebuild forever.
     */
    public void prune(String owner, String partition, long facts, long masked) {
        String ownerKey = owner == null ? "" : owner;
        String partitionKey = partition == null ? "" : partition;
        slots.values()
                .removeIf(slot -> slot.owner().equals(ownerKey)
                        && slot.partition().equals(partitionKey)
                        && (slot.facts() != facts || slot.masked() != masked));
    }

    /** Drops every slot and moves the epoch: an owner or session switch, or a backend change. */
    public void invalidate() {
        slots.clear();
        epoch++;
    }

    public int size() {
        return slots.size();
    }
}
