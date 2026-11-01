package dev.friendai.features.mining;

import dev.friendai.features.navigation.pathing.Cell;
import dev.friendai.features.navigation.pathing.CellView;
import dev.friendai.features.navigation.pathing.Move;
import dev.friendai.features.navigation.pathing.Node;
import dev.friendai.features.navigation.pathing.PathStep;
import dev.friendai.features.navigation.pathing.Pos;
import java.util.HashSet;
import java.util.Set;

/** Plain-data checks shared by mining goals and mining-specific edge policies. */
public final class MiningSafety {

    private static final int[][] NEIGHBORS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    private MiningSafety() {}

    /** Whether a target may be extracted from this stance without removing its feet, head, or support. */
    public static boolean canBreak(CellView view, Node stance, Pos target, Set<Pos> protectedSupports) {
        if (target.equals(stance.pos())
                || target.equals(stance.head())
                || target.equals(stance.support())
                || protectedSupports.contains(target)
                || !safeNode(view, stance)
                || !safeTarget(view, target)) {
            return false;
        }
        // Keep a full block between the entity's nominal cell and a floor resource: the live footprint can extend
        // over an adjacent support when movement has not centered perfectly in its destination cell.
        if (target.y() == stance.y() - 1
                && Math.max(Math.abs(target.x() - stance.x()), Math.abs(target.z() - stance.z())) == 1) {
            return false;
        }
        return true;
    }

    /** A supported approach cell may still need its feet or head cleared by the final planned edge. */
    public static boolean canApproach(CellView view, Node stance, Pos target, Set<Pos> protectedSupports) {
        if (target.equals(stance.pos())
                || target.equals(stance.head())
                || target.equals(stance.support())
                || protectedSupports.contains(target)
                || !view.at(stance.support()).supports()
                || view.at(stance.support()).kind() == Cell.Kind.UNLOADED
                || !safeTarget(view, target)) {
            return false;
        }
        if (target.y() == stance.y() - 1
                && Math.max(Math.abs(target.x() - stance.x()), Math.abs(target.z() - stance.z())) == 1) {
            return false;
        }
        Cell feet = view.at(stance.pos());
        Cell head = view.at(stance.head());
        if ((!feet.free() && !feet.breakable()) || (!head.free() && !head.breakable())) {
            return false;
        }
        return true;
    }

    private static boolean safeTarget(CellView view, Pos target) {
        Cell targetCell = view.at(target);
        if (targetCell.kind() == Cell.Kind.UNLOADED
                || targetCell.hazard()
                || targetCell.falls()
                || targetCell.kind() == Cell.Kind.WATER
                || targetCell.kind() == Cell.Kind.LAVA) {
            return false;
        }
        for (int[] d : NEIGHBORS) {
            Cell neighbor = view.at(target.x() + d[0], target.y() + d[1], target.z() + d[2]);
            if (neighbor.kind() == Cell.Kind.UNLOADED
                    || neighbor.kind() == Cell.Kind.WATER
                    || neighbor.kind() == Cell.Kind.LAVA
                    || neighbor.falls()) {
                return false;
            }
        }
        return true;
    }

    /** Whether this edge is cardinal, dry, supported, and can be traversed back without more world changes. */
    public static boolean reversible(CellView view, PathStep step) {
        int dx = step.to().x() - step.from().x();
        int dy = step.to().y() - step.from().y();
        int dz = step.to().z() - step.from().z();
        if (Math.abs(dx) + Math.abs(dz) != 1 || Math.abs(dy) > 1 || !allowedMove(step.move(), dy)) {
            return false;
        }

        Set<Pos> removed = new HashSet<>(step.breaks());
        CellView projected = (x, y, z) -> removed.contains(new Pos(x, y, z)) ? Cell.AIR : view.at(x, y, z);
        // The stance a step leaves needs free feet and head, but not a floor: a companion that broke the rim under
        // itself rests its box on a neighbour's edge with nothing under its own cell, and demanding a floor there
        // refused every step away from it, leaving it unable to plan at all. Where the step lands still has to hold.
        if (!standable(projected, step.from()) || !safeNode(projected, step.to())) {
            return false;
        }
        // A one-block descent is reversible only if its inverse ascent has clear feet and head cells.
        if (dy < 0
                && (!projected.at(step.from().pos()).free()
                        || !projected.at(step.from().head()).free())) {
            return false;
        }
        for (Pos block : step.breaks()) {
            if (!view.at(block).breakable()
                    || block.equals(step.from().support())
                    || block.equals(step.to().support())) {
                return false;
            }
        }
        return step.places().isEmpty();
    }

    private static boolean allowedMove(Move move, int dy) {
        return switch (move) {
            case WALK -> dy == 0;
            case DIG -> dy == 0;
            case ASCEND, DIG_ASCEND -> dy == 1;
            case DESCEND, DIG_DESCEND -> dy == -1;
            default -> false;
        };
    }

    private static boolean standable(CellView view, Node node) {
        Cell feet = view.at(node.pos());
        Cell head = view.at(node.head());
        return feet.kind() != Cell.Kind.WATER && feet.free() && head.kind() != Cell.Kind.WATER && head.free();
    }

    private static boolean safeNode(CellView view, Node node) {
        Cell feet = view.at(node.pos());
        Cell head = view.at(node.head());
        Cell support = view.at(node.support());
        return feet.kind() != Cell.Kind.WATER
                && feet.free()
                && head.kind() != Cell.Kind.WATER
                && head.free()
                && support.supports()
                && support.kind() != Cell.Kind.UNLOADED;
    }
}
