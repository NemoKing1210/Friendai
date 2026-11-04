package dev.friendai.features.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The hand-overs one companion performed itself, newest last and bounded: what it dropped for its owner, how many,
 * when, and which item entity now carries it.
 *
 * <p>A delivery is evidence, not proof: the owner's own inventory is still the confirmation. An entry counts only
 * while its drop entity is still in the world, so a stack that despawned, fell into lava or was picked up and then
 * lost stops propping up a criterion that claims the owner has it.
 */
public final class DeliveredLedger {
    /** How many hand-overs one companion remembers; older ones say nothing the mission block needs. */
    public static final int MAX_ENTRIES = 32;
    /** How long a hand-over stays evidence: long enough for the owner to pick it up, short enough to expire. */
    public static final long WINDOW_TICKS = 600;

    public static final DeliveredLedger EMPTY = new DeliveredLedger(List.of());

    /**
     * One dropped stack.
     *
     * @param itemId registry id of what left the companion
     * @param count  how many items the drop carries
     * @param tick   game time of the hand-over
     * @param drop   the item entity that now carries it
     */
    public record Entry(String itemId, int count, long tick, UUID drop) {
        public Entry {
            itemId = itemId == null ? "" : itemId.strip();
            drop = Objects.requireNonNull(drop, "drop");
        }

        public boolean carriesAnything() {
            return !itemId.isEmpty() && count > 0;
        }
    }

    private final List<Entry> entries;

    private DeliveredLedger(List<Entry> entries) {
        this.entries = entries;
    }

    /** The ledger plus one hand-over; an entry that carries nothing is not recorded, and the oldest falls off. */
    public DeliveredLedger record(Entry entry) {
        if (entry == null || !entry.carriesAnything()) {
            return this;
        }
        List<Entry> next = new ArrayList<>(entries);
        next.add(entry);
        while (next.size() > MAX_ENTRIES) {
            next.remove(0);
        }
        return new DeliveredLedger(List.copyOf(next));
    }

    /**
     * How many of this item the companion handed over recently and whose drop is still in the world.
     *
     * @param nowTick      current game time
     * @param stillInWorld whether the drop entity is still alive and still carries the item
     */
    public int count(String itemId, long nowTick, Predicate<UUID> stillInWorld) {
        String wanted = itemId == null ? "" : itemId.strip();
        if (wanted.isEmpty() || entries.isEmpty()) {
            return 0;
        }
        long oldest = nowTick - WINDOW_TICKS;
        int total = 0;
        for (Entry entry : entries) {
            if (!entry.itemId().equals(wanted) || entry.tick() < oldest || entry.tick() > nowTick) {
                continue;
            }
            if (stillInWorld.test(entry.drop())) {
                total += entry.count();
            }
        }
        return total;
    }

    public List<Entry> entries() {
        return entries;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }
}
