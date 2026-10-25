package dev.friendai.task.nav;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.movement.PacePolicy;

/** Applies the pace policy to one companion: turns sprinting on or off from its mode and situation. */
public final class Pace {
    private final CompanionEntity companion;

    public Pace(CompanionEntity companion) {
        this.companion = companion;
    }

    /**
     * Re-decides whether the companion runs.
     *
     * @param following     true when the companion is trailing its owner
     * @param ownerDistance distance to the owner in blocks, or NaN when not following
     */
    public void update(boolean following, double ownerDistance) {
        boolean crouched = companion.body().sneaking() || companion.body().sitting();
        boolean run = !crouched && PacePolicy.shouldRun(
                companion.paceMode(), following, ownerDistance, companion.canSprint(), companion.isSprinting());
        if (run != companion.isSprinting()) {
            companion.setSprinting(run);
        }
    }
}
