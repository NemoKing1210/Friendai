package dev.friendai.world.movement;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.foundation.movement.PacePolicy;

/** Applies the pace policy to one companion: turns sprinting on or off from its mode and situation. */
public final class Pace {
    private final CompanionEntity companion;
    /** A route asked for a running start into a leap; the sprint lasts until the route withdraws it. */
    private boolean runUpRequested;

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
        boolean policy = following
                ? PacePolicy.shouldRun(
                        companion.paceMode(), true, distance, companion.canSprint(), companion.isSprinting())
                : PacePolicy.shouldRunOnPath(
                        companion.paceMode(), pathDistance, companion.canSprint(), companion.isSprinting());
        // The pace policy stays the only writer of the sprint flag: a leap's run-up is a request it honours, not a
        // second writer that fights it every tick.
        boolean run = companion.canSprint() && !crouched && !companion.onClimbable() && (runUpRequested || policy);
        if (run != companion.isSprinting()) {
            companion.setSprinting(run);
        }
    }

    /**
     * Asks for a running start into a leap, or withdraws the request once the leap is behind the route. It is applied
     * here as well as honoured by {@link #update}: a route driven by its own navigator (an approach, a journey, a
     * material gather) never ticks the move helper, so a request waiting for the next policy update would never land.
     */
    public void requestRunUp(boolean requested) {
        this.runUpRequested = requested;
        boolean sprint = requested && companion.canSprint();
        if (sprint != companion.isSprinting()) {
            companion.setSprinting(sprint);
        }
    }
}
