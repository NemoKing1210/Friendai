package dev.friendai.features.navigation.pathing;

import java.util.List;

/**
 * A goal of standing where a drop can actually be picked up. The test is the same volume the pickup uses: the
 * companion's body box inflated by the pickup reach, against the item's own box. That is what makes a drop resting in
 * a one-block pit a goal from the rim, where the eyes are farther than an interaction reach but the body still covers
 * the item.
 *
 * <p>The stance may stand in the drop's cell: an item is taken by walking over it, unlike a block to act on.
 */
public record GoalPickup(List<DropPoint> drops, double reach) implements PathGoal {
    /** The companion's body, matching {@code FriendaiEntities}: 0.6 wide, 1.8 tall. */
    public static final double BODY_WIDTH = 0.6;

    public static final double BODY_HEIGHT = 1.8;
    /** An item entity's box, as {@code EntityType.ITEM} sizes it. */
    public static final double ITEM_WIDTH = 0.25;

    public static final double ITEM_HEIGHT = 0.25;
    /** The widest horizontal gap, body centre to item box, that still overlaps. */
    private static final double HORIZONTAL = BODY_WIDTH / 2.0 + ITEM_WIDTH / 2.0;

    public GoalPickup {
        drops = List.copyOf(drops);
    }

    @Override
    public boolean isGoal(Node n, CellView view) {
        return reaches(n.x() + 0.5, n.y(), n.z() + 0.5, reach);
    }

    /** Whether a companion standing at this exact spot takes one of the drops. */
    public boolean reaches(double bodyX, double feetY, double bodyZ, double reach) {
        for (DropPoint drop : drops) {
            if (reaches(bodyX, feetY, bodyZ, drop, reach)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the bodies overlap for picking up this one drop; the pickup itself uses these same boxes. */
    public static boolean reaches(double bodyX, double feetY, double bodyZ, DropPoint drop, double reach) {
        double horizontal = HORIZONTAL + reach;
        double lowest = feetY - reach - ITEM_HEIGHT;
        double highest = feetY + BODY_HEIGHT + reach;
        return Math.abs(drop.x() - bodyX) <= horizontal
                && Math.abs(drop.z() - bodyZ) <= horizontal
                && drop.y() + ITEM_HEIGHT >= lowest
                && drop.y() <= highest;
    }

    @Override
    public double heuristic(Node n, CostModel costs) {
        double best = Double.POSITIVE_INFINITY;
        for (DropPoint drop : drops) {
            double dx = n.x() + 0.5 - drop.x();
            double dy = n.y() - drop.y();
            double dz = n.z() + 0.5 - drop.z();
            best = Math.min(best, Math.max(0, Math.sqrt(dx * dx + dy * dy + dz * dz) - reach));
        }
        return best == Double.POSITIVE_INFINITY ? 0 : best * GoalReach.perBlock(costs);
    }
}
