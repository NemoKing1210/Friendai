package dev.friendai.features.navigation.movement;

import dev.friendai.features.navigation.pathing.Move;

/**
 * Decides whether a companion has finished a path step. Besides standing in the arrival radius, a body that momentum
 * carried past the target cell's centre but still inside that cell has arrived: steering it back to the centre would
 * turn it around, and the route would visibly step back one block before going on.
 */
public final class ArrivalPolicy {

    /** The furthest past the centre a body may be and still count as inside the cell. */
    private static final double HALF_CELL = 0.5;

    private ArrivalPolicy() {}

    /**
     * @param move the step's movement
     * @param stepDx the step's horizontal x change (target cell minus source cell)
     * @param stepDz the step's horizontal z change
     * @param dx the body's x offset from the target cell's centre
     * @param dz the body's z offset from the target cell's centre
     * @param radius the arrival radius around the centre
     * @param level whether the body's height matches the target's
     */
    public static boolean arrived(
            Move move, int stepDx, int stepDz, double dx, double dz, double radius, boolean level) {
        if (dx * dx + dz * dz <= radius * radius) {
            return level;
        }
        if (!level || !carriesMomentum(move)) {
            return false;
        }
        int sx = Integer.signum(stepDx);
        int sz = Integer.signum(stepDz);
        if (sx == 0 && sz == 0) {
            return false;
        }
        double along = dx * sx + dz * sz;
        if (along <= 0) {
            return false;
        }
        if (sx != 0 && sz != 0) {
            // A diagonal step has no single across axis: staying inside the cell on both is what counts.
            return Math.abs(dx) <= HALF_CELL && Math.abs(dz) <= HALF_CELL;
        }
        double across = Math.abs(sx == 0 ? dx : dz);
        return along <= HALF_CELL && across <= HALF_CELL;
    }

    /** Steps that end on solid ground at the speed the body arrived with, so it may slide past the centre. */
    private static boolean carriesMomentum(Move move) {
        return move == Move.WALK || move == Move.WALK_DIAGONAL || move == Move.ASCEND || move == Move.DESCEND;
    }
}
