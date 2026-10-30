package dev.friendai.features.construction;

/**
 * How long the companion waits between two block placements, in server ticks.
 *
 * <p>A player holding right-click places one block every {@code Minecraft.rightClickDelay} ticks; the companion used
 * to place one per tick, layering a survival wall twenty times faster than a player can. The pace is ticked once per
 * server tick and spent by the placement choke point, so every task that places blocks shares one honest rate. All of
 * it is two integers: no clock, no allocation, and it keeps its countdown when a task is held.
 */
public final class PlacementPace {
    public static final int MIN_INTERVAL = 1;
    public static final int MAX_INTERVAL = 40;
    /** One block every four ticks in survival, matching a player's right-click delay. */
    public static final int DEFAULT_INTERVAL = 4;

    private int ticksLeft;

    /** Whether a placement may happen now. */
    public boolean ready() {
        return ticksLeft <= 0;
    }

    /**
     * Records a placement that just happened: the next one waits {@code interval} ticks. Called once per placed
     * block, including the second half of a bed or a door.
     */
    public void spent(int interval) {
        this.ticksLeft = clamp(interval);
    }

    /** Advances the countdown by one server tick. */
    public void tick() {
        if (ticksLeft > 0) {
            ticksLeft--;
        }
    }

    /** Forgets any wait, so a stopped or cancelled job does not delay the next one. */
    public void reset() {
        ticksLeft = 0;
    }

    public int ticksLeft() {
        return ticksLeft;
    }

    /**
     * The interval to use for one companion: creative stays at one block per tick, which is the printer players know,
     * while survival takes the configured pace clamped to a sane range.
     */
    public static int interval(int configured, boolean creative) {
        return creative ? MIN_INTERVAL : clamp(configured);
    }

    public static int clamp(int interval) {
        return Math.clamp(interval, MIN_INTERVAL, MAX_INTERVAL);
    }
}
