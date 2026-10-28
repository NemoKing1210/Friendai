package dev.friendai.task.inventory;

import dev.friendai.task.TaskContext;
import org.jetbrains.annotations.Nullable;

/**
 * Waits a few ticks for the drops an interaction just made to fall and leave their pickup delay, taking anything that
 * comes to rest at the companion's feet. Used where a task acted on a block and needs the drop in the inventory before
 * it can move on; a route to drops further away is {@link DropCollector}'s business.
 */
public final class DropSettle {
    /**
     * The radius a settle takes drops from: a drop from a block or animal acted on at arm's length lands at the
     * target, and the companion may stand {@link dev.friendai.features.navigation.minecraft.nav.Approach#REACH} (4.0) eye-blocks away and a
     * little higher than its feet, so anything under that distance can be just out of a tighter radius.
     */
    public static final double INTERACT_RADIUS = 6.0;

    private final int limit;
    private final double radius;
    private final @Nullable ItemMatcher filter;
    private long startedAt = Long.MIN_VALUE;
    private int collected;

    /** @param limit how many game ticks to keep trying before giving up on the rest */
    public DropSettle(int limit, double radius, @Nullable ItemMatcher filter) {
        this.limit = limit;
        this.radius = radius;
        this.filter = filter;
    }

    /**
     * Ticks the wait; true when the settle is over. Takes delay-expired drops at the feet each call. The wait is
     * measured in game ticks rather than in calls, because the pickup delay it waits out is a game-tick delay: a
     * runner that ticks a task twice in one tick, as a test may, must not cut the delay short.
     */
    public boolean tick(TaskContext c) {
        collected += DropCollector.pickupAtFeet(c, radius, filter);
        long now = c.level().getGameTime();
        if (startedAt == Long.MIN_VALUE) {
            startedAt = now;
        }
        return now - startedAt >= limit;
    }

    /** How many items this settle has moved into the inventory. */
    public int collected() {
        return collected;
    }
}
