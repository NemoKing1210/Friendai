package dev.friendai.features.knowledge;

/**
 * How long what the companion saw inside a container stays true, as a balance between a player's memory and a
 * convenient companion. For {@link #LIVE_TICKS} after it opened a container it still sees what happens inside it (the
 * owner's own changes included) without another trip; after that the record is remembered, a hint that may be out of
 * date, until {@link #REMEMBER_TICKS}; a recheck opens the container again and starts a new live window.
 */
public final class ContainerFreshness {
    /** Five minutes of game time: a typical stay at the base. */
    public static final long LIVE_TICKS = 6_000;

    /** One game day: past it a record is stale and no longer planned with. */
    public static final long REMEMBER_TICKS = 24_000;

    private ContainerFreshness() {}

    /** True while the companion still sees inside a container it opened at {@code observedTick}. */
    public static boolean live(long observedTick, long now) {
        return now - observedTick <= LIVE_TICKS;
    }

    /** True while a record is still worth planning with, live or not. */
    public static boolean remembered(long observedTick, long now) {
        return now - observedTick <= REMEMBER_TICKS;
    }
}
