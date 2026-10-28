package dev.friendai.placed;

import dev.friendai.pathing.Pos;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * One owner's placement ledger: every block the companion placed that may still be there, oldest first. Pure and
 * mutable; the store decides when to persist. A repeated placement at the same cell updates the entry and moves it
 * to the newest position, so eviction always drops the oldest fact, not the most recent one.
 */
public final class PlacedBook {

    private record Key(String dimension, Pos pos) {}

    private final PlacedLimits limits;
    private final LinkedHashMap<Key, PlacedBlock> entries = new LinkedHashMap<>();
    private long revision;
    private boolean dirty;

    public PlacedBook(PlacedLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /** Records one placement, replacing an older entry for the same cell. */
    public void record(PlacedBlock block) {
        Objects.requireNonNull(block, "block");
        Key key = new Key(block.dimension(), block.pos());
        entries.remove(key);
        entries.put(key, block);
        while (entries.size() > limits.maxEntries()) {
            Iterator<Key> oldest = entries.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
        dirty = true;
        revision++;
    }

    /** Forgets the entry for one cell; false when there was none. */
    public boolean remove(String dimension, Pos pos) {
        if (entries.remove(new Key(dimension, pos)) == null) {
            return false;
        }
        dirty = true;
        revision++;
        return true;
    }

    /** Drops every entry the predicate rejects; returns how many were dropped. */
    public int prune(Predicate<PlacedBlock> keep) {
        Objects.requireNonNull(keep, "keep");
        int removed = 0;
        Iterator<Map.Entry<Key, PlacedBlock>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            if (!keep.test(it.next().getValue())) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            dirty = true;
            revision++;
        }
        return removed;
    }

    /** Every entry, oldest first. */
    public List<PlacedBlock> all() {
        return List.copyOf(entries.values());
    }

    /** The entries in one dimension, oldest first. */
    public List<PlacedBlock> inDimension(String dimension) {
        List<PlacedBlock> found = new ArrayList<>();
        for (PlacedBlock block : entries.values()) {
            if (block.dimension().equals(dimension)) {
                found.add(block);
            }
        }
        return found;
    }

    public int size() {
        return entries.size();
    }

    public long revision() {
        return revision;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void markClean() {
        dirty = false;
    }

    public PlacedLimits limits() {
        return limits;
    }
}
