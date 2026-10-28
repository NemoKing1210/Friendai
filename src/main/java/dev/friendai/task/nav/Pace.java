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
        update(following, ownerDistance, 0.0);
    }

    /**
     * Re-decides whether the companion runs, including the case where the pace distance is the length of the route
     * it is walking rather than the distance to its owner.
     */
    public void update(boolean following, double distance, double pathDistance) {
        boolean crouched = companion.body().sneaking() || companion.body().sitting();
        boolean run = !crouched && (following
                ? PacePolicy.shouldRun(companion.paceMode(), true, distance, companion.canSprint(),
                        companion.isSprinting())
                : PacePolicy.shouldRunOnPath(companion.paceMode(), pathDistance, companion.canSprint(),
                        companion.isSprinting()));
        if (run != companion.isSprinting()) {
            companion.setSprinting(run);
        }
    }
}
