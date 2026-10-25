package dev.friendai.task.inventory;

import dev.friendai.task.TaskContext;
import org.jetbrains.annotations.Nullable;

/**
 * Waits a few ticks for the drops an interaction just made to fall and leave their pickup delay, taking anything that
 * comes to rest at the companion's feet. Used where a task acted on a block and needs the drop in the inventory before
 * it can move on; a route to drops further away is {@link DropCollector}'s business.
 */
public final class DropSettle {
    private final int limit;
    private final double radius;
    private final @Nullable ItemMatcher filter;
    private int ticks;
    private int collected;

    /** @param limit how many ticks to keep trying before giving up on the rest */
    public DropSettle(int limit, double radius, @Nullable ItemMatcher filter) {
        this.limit = limit;
        this.radius = radius;
        this.filter = filter;
    }

    /** Ticks the wait; true when the settle is over. Takes delay-expired drops at the feet each tick. */
    public boolean tick(TaskContext c) {
        collected += DropCollector.pickupAtFeet(c, radius, filter);
        return ++ticks >= limit;
    }

    /** How many items this settle has moved into the inventory. */
    public int collected() {
        return collected;
    }
}
