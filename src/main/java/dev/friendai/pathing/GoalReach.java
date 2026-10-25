package dev.friendai.pathing;

import java.util.List;

/** A goal of getting close enough to act on one of several blocks: a log to mine, a chest to open. */
public record GoalReach(List<Pos> targets, double reach, double eyeHeight) implements PathGoal {

    @Override
    public boolean isGoal(Node n, CellView view) {
        return reachedTarget(n, view) != null;
    }

    @Override
    public double heuristic(Node n, CostModel costs) {
        double best = Double.POSITIVE_INFINITY;
        for (Pos target : targets) {
            best = Math.min(best, Math.max(0, distance(n, target) - reach));
        }
        return best == Double.POSITIVE_INFINITY ? 0 : best * perBlock(costs);
    }

    /** Each move's cost divided by its maximum geometric progress; default costs retain a bound of one. */
    private static double perBlock(CostModel costs) {
        double walk = Math.min(costs.walk(), costs.diagonal()) / Math.sqrt(2.0);
        double jump = Math.min((costs.walk() + costs.jump()) / 2.0, costs.jump() + costs.place());
        // For any depth k >= 1, (walk + k * fall) / sqrt(k*k + 2) is bounded by these endpoints.
        double descend = Math.min(costs.fall(), (costs.walk() + costs.fall()) / Math.sqrt(3.0));
        double vertical = Math.min(costs.swim(), costs.climb());
        return Math.max(0, Math.min(1.0, Math.min(Math.min(walk, jump), Math.min(descend, vertical))));
    }

    @Override
    public boolean protects(Pos p) {
        return targets.contains(p);
    }

    /**
     * The first target the companion can act on from {@code n}, or null when there is none. Standing in the target's cell
     * does not count: the feet or the head would be in the way of whatever the companion wants to do there.
     */
    public Pos reachedTarget(Node n, CellView view) {
        for (Pos target : targets) {
            if (!occupies(n, target)
                    && distance(n, target) <= reach
                    && LineOfSight.clear(view, eyeX(n), eyeY(n), eyeZ(n), target)) {
                return target;
            }
        }
        return null;
    }

    private static boolean occupies(Node n, Pos target) {
        return n.x() == target.x() && n.z() == target.z() && (n.y() == target.y() || n.y() + 1 == target.y());
    }

    private double distance(Node n, Pos target) {
        double dx = eyeX(n) - (target.x() + 0.5);
        double dy = eyeY(n) - (target.y() + 0.5);
        double dz = eyeZ(n) - (target.z() + 0.5);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double eyeX(Node n) {
        return n.x() + 0.5;
    }

    private double eyeY(Node n) {
        return n.y() + eyeHeight;
    }

    private static double eyeZ(Node n) {
        return n.z() + 0.5;
    }
}
