package dev.friendai.features.memory.retrieval;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A bounded, versioned derived cache of fact vectors. It is not a source of truth: every entry carries the fact's text
 * revision, so a forget or replacement makes the old vector unreachable even if a late async result arrives. Eviction
 * is oldest-insertion first; a refresh moves an entry to the newest position.
 */
public final class RetrievalCache {
    public static final int MAX_ENTRIES = 256;

    public record Entry(int factId, long revision, float[] vector) {
        public Entry {
            if (factId < 0) throw new IllegalArgumentException("factId");
            vector = vector == null ? new float[0] : vector.clone();
        }

        @Override
        public float[] vector() {
            return vector.clone();
        }
    }

    private final LinkedHashMap<Integer, Entry> entries = new LinkedHashMap<>();

    /** The entry only while it matches the current text revision; a stale entry is a miss. */
    public Optional<Entry> get(int factId, long revision) {
        Entry entry = entries.get(factId);
        return entry != null && entry.revision() == revision ? Optional.of(entry) : Optional.empty();
    }

    public void put(int factId, long revision, float[] vector) {
        entries.remove(factId);
        entries.put(factId, new Entry(factId, revision, vector));
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(entries.keySet().iterator().next());
        }
    }

    /** Drops one fact immediately, as a forget would. */
    public boolean remove(int factId) {
        return entries.remove(factId) != null;
    }

    /** Drops every vector for an owner/principal change or a backend/version change. */
    public void clear() {
        entries.clear();
    }

    public int size() {
        return entries.size();
    }

    public Map<Integer, Entry> snapshot() {
        return Map.copyOf(entries);
    }

    /** Whether an entry's revision no longer matches the fact's current text. */
    public static boolean stale(Entry entry, long currentRevision) {
        return entry == null || entry.revision() != currentRevision;
    }
}
