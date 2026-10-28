package dev.friendai.features.navigation.movement;

import dev.friendai.features.navigation.pathing.Node;
import dev.friendai.features.navigation.pathing.PathStep;
import java.util.List;

/**
 * The yaw a walking body should hold for one tick while it follows a planned route.
 *
 * <p>Vanilla {@code MoveControl} turns the body toward the point it is given, up to 90° per tick. Aiming it at the
 * next node's centre is right for convergence, but a descent whips around: the landing cell is below and the fall
 * carries the body past it while the step cannot advance, so the wanted point ends up behind. Here the desired
 * direction is always the step's own end, bounded to a rate the eye can follow, and a drop in progress holds the
 * heading entirely until the body is on the ground again.
 *
 * <p>Pure and allocation-free: it is read every tick by the executor and only does arithmetic.
 */
public final class PathSteering {

    /** An aim closer than this carries no direction: keep the current heading. */
    public static final double MIN_AIM = 0.35;
    /** The most the body may turn in one tick. */
    public static final float MAX_TURN = 30.0f;

    private PathSteering() {}

    /**
     * The yaw to walk with this tick.
     *
     * @param steps the whole route
     * @param index the step being executed
     * @param x the walker's horizontal position
     * @param z the walker's horizontal position
     * @param currentYaw the body's yaw now
     * @param hold true while a drop is in progress, when any re-aim would point at the cell already left
     * @return a yaw within {@link #MAX_TURN} of {@code currentYaw}
     */
    public static float yaw(List<PathStep> steps, int index, double x, double z, float currentYaw, boolean hold) {
        if (hold || index < 0 || index >= steps.size()) {
            return Facing.normalize(currentYaw);
        }
        Node to = steps.get(index).to();
        double dx = to.x() + 0.5 - x;
        double dz = to.z() + 0.5 - z;
        if (Math.hypot(dx, dz) < MIN_AIM) {
            return Facing.normalize(currentYaw);
        }
        float desired = Facing.yawToward(dx, dz);
        return Facing.stepToward(currentYaw, desired, MAX_TURN);
    }
}
