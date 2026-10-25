package dev.friendai.entity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The distinct chunk tickets held for loaded companions, kept in sync with what is currently needed.
 *
 * <p>Vanilla stores tickets per chunk as a set of (type, level) pairs and no owner, so two companions in one chunk
 * share a single ticket. This set does the same bookkeeping: a key is added when it is first wanted and removed only
 * when nothing wants it any more.
 */
public final class CompanionTicketSet {
    /** One chunk ticket: the dimension it belongs to, the packed chunk and the configured entity-ticking radius. */
    public record Key(String dimension, long chunk, int radius) {}

    /** What changed: keys to add first, then keys to remove. */
    public record Change(List<Key> add, List<Key> remove) {}

    private final Set<Key> held = new HashSet<>();

    /** Brings the held set to {@code desired} and reports the tickets to add and remove. */
    public Change update(Collection<Key> desired) {
        Set<Key> wanted = new HashSet<>(desired);
        List<Key> add = new ArrayList<>();
        List<Key> remove = new ArrayList<>();
        for (Key key : wanted) {
            if (held.add(key)) {
                add.add(key);
            }
        }
        for (Key key : held) {
            if (!wanted.contains(key)) {
                remove.add(key);
            }
        }
        held.removeAll(remove);
        return new Change(List.copyOf(add), List.copyOf(remove));
    }

    /** How many distinct tickets are currently held. */
    public int size() {
        return held.size();
    }
}
