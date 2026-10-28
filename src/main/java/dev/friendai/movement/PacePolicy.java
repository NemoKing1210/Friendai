package dev.friendai.movement;

/** Decides whether the companion should run, with hysteresis in automatic mode. */
public final class PacePolicy {

    /** Distance to the owner beyond which an automatic follower starts running. */
    public static final double RUN_START_DISTANCE = 10.0;
    /** Distance to the owner below which an automatic follower stops running. */
    public static final double RUN_STOP_DISTANCE = 5.0;

    private PacePolicy() {
    }

    public static boolean shouldRun(PaceMode mode, boolean following, double ownerDistance,
                                    boolean canSprint, boolean running) {
        if (!canSprint) {
            return false;
        }
        return switch (mode) {
            case RUN -> true;
            case WALK -> false;
            case AUTO -> {
                if (!following) {
                    yield false;
                }
                yield running ? ownerDistance > RUN_STOP_DISTANCE : ownerDistance > RUN_START_DISTANCE;
            }
        };
    }

    /**
     * The same decision for a walked route: the distance that matters is the length still to walk, not the owner.
     * Hysteresis is identical, so a long leg starts at ten blocks and stops at five rather than flickering.
     */
    public static boolean shouldRunOnPath(PaceMode mode, double remainingBlocks, boolean canSprint, boolean running) {
        if (!canSprint) {
            return false;
        }
        return switch (mode) {
            case RUN -> true;
            case WALK -> false;
            case AUTO -> running ? remainingBlocks > RUN_STOP_DISTANCE : remainingBlocks > RUN_START_DISTANCE;
        };
    }
}
