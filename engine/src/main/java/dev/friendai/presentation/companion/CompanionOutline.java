package dev.friendai.presentation.companion;

/**
 * Decides whether the owner's client outlines its own companion.
 *
 * <p>The outline answers "where is it" for an owner who cannot see the companion, so it shows only when the companion
 * is beyond {@link #FAR_BLOCKS} blocks or has no line of sight. It never shows for another player's companion, and the
 * display option turns it off entirely. The renderer keeps the world reads (distance, sight) and the render-state
 * colour; this class is the rule alone, so it is unit-tested without a frame.
 */
public final class CompanionOutline {
    /** Beyond this the companion is not obviously in view even on flat ground. Matches the option's tooltip. */
    public static final double FAR_BLOCKS = 12.0;

    private static final double FAR_BLOCKS_SQUARED = FAR_BLOCKS * FAR_BLOCKS;

    private CompanionOutline() {}

    /**
     * The cheap half of the decision, usable before any world read: the option is on and the companion belongs to the
     * viewing player.
     */
    public static boolean applies(boolean enabled, boolean ownerViewing) {
        return enabled && ownerViewing;
    }

    /**
     * The outline is drawn only when {@link #applies} holds and the companion is farther than {@link #FAR_BLOCKS} (the
     * limit itself is still "in view") or the player has no line of sight to it.
     */
    public static boolean shouldDraw(
            boolean enabled, boolean ownerViewing, double distanceSquared, boolean lineOfSight) {
        return applies(enabled, ownerViewing) && (distanceSquared > FAR_BLOCKS_SQUARED || !lineOfSight);
    }
}
