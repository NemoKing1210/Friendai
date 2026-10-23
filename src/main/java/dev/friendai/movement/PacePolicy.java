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
}
