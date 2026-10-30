package dev.friendai.features.navigation.movement;

import dev.friendai.features.navigation.pathing.Move;
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

    /** The slowest share of its full speed a body is held to for a corner or the end of the route. */
    public static final double MIN_SPEED = 0.35;
    /** Blocks before a corner over which the body bleeds speed down to the corner's speed. */
    private static final double BRAKE_LENGTH = 2.0;
    /** Distance to the corner under which the body is already at the corner's speed. */
    private static final double BRAKE_FLOOR = 0.3;
    /** The corner speed of the last node, where the body has to stop and not slide on past it. */
    private static final double STOP_SPEED = 0.4;
    /** The turn, in degrees, at which a corner takes the body down to {@link #MIN_SPEED}. */
    private static final double FULL_BRAKE_TURN = 120.0;

    private PathSteering() {}

    /**
     * The share of full speed to walk with this tick. Ground friction keeps a running body's velocity pointing the
     * way it was going for several ticks after it turns, so a body that takes a corner at full speed swings wide
     * and runs past the node; it has to arrive at the corner already slow. The speed falls linearly over the last
     * {@value #BRAKE_LENGTH} blocks to a corner speed set by how sharp the next turn is, and to
     * {@value #STOP_SPEED} at the end of the route. A leap and a straight line into one keep full speed.
     *
     * @param steps the whole route
     * @param index the step being executed
     * @param x the walker's horizontal position
     * @param z the walker's horizontal position
     * @return a factor in {@code [MIN_SPEED, 1]} of the movement speed
     */
    public static double speed(List<PathStep> steps, int index, double x, double z) {
        if (index < 0 || index >= steps.size()) {
            return 1.0;
        }
        PathStep step = steps.get(index);
        if (step.move() == Move.JUMP_GAP) {
            return 1.0;
        }
        double cx = step.to().x() - step.from().x();
        double cz = step.to().z() - step.from().z();
        if (cx == 0.0 && cz == 0.0) {
            return 1.0;
        }
        double corner = STOP_SPEED;
        if (index + 1 < steps.size()) {
            PathStep next = steps.get(index + 1);
            double nx = next.to().x() - next.from().x();
            double nz = next.to().z() - next.from().z();
            if (nx != 0.0 || nz != 0.0) {
                double turn = Math.toDegrees(Math.abs(Math.atan2(cx * nz - cz * nx, cx * nx + cz * nz)));
                corner = Math.max(
                        MIN_SPEED, 1.0 - (1.0 - MIN_SPEED) * Math.min(turn, FULL_BRAKE_TURN) / FULL_BRAKE_TURN);
            }
        }
        double distance = Math.hypot(step.to().x() + 0.5 - x, step.to().z() + 0.5 - z);
        double brake = Math.max(0.0, Math.min(1.0, (distance - BRAKE_FLOOR) / BRAKE_LENGTH));
        return corner + (1.0 - corner) * brake;
    }

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
