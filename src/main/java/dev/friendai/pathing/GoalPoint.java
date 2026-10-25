package dev.friendai.pathing;

import java.util.Optional;

/** A goal near a single point, as {@code MoveHelper} wants: within a horizontal radius and a height band. */
public record GoalPoint(double x, double y, double z, double horizontal, double vertical)
        implements PathGoal {

    @Override
    public boolean protects(Pos p) {
        int gx = (int) Math.floor(x);
        int gy = (int) Math.floor(y);
        int gz = (int) Math.floor(z);
        return p.x() == gx && p.z() == gz && (p.y() == gy || p.y() == gy - 1);
    }

    @Override
    public boolean isGoal(Node n, CellView view) {
        double dx = n.x() + 0.5 - x;
        double dz = n.z() + 0.5 - z;
        return Math.sqrt(dx * dx + dz * dz) <= horizontal && Math.abs(n.y() - y) <= vertical;
    }

    @Override
    public double heuristic(Node n, CostModel costs) {
        double dx = n.x() + 0.5 - x;
        double dz = n.z() + 0.5 - z;
        double horizontalGap = Math.max(0, Math.sqrt(dx * dx + dz * dz) - horizontal);
        double above = Math.max(0, y - n.y() - vertical);
        double below = Math.max(0, n.y() - y - vertical);
        return Math.max(horizontalWeight(costs) * horizontalGap,
                upWeight(costs) * above + downWeight(costs) * below);
    }

    private static double horizontalWeight(CostModel costs) {
        double walk = Math.min(costs.walk(), costs.diagonal() / Math.sqrt(2.0));
        double jump = (costs.walk() + costs.jump()) / 2.0;
        double descend = (costs.walk() + costs.fall()) / Math.sqrt(2.0);
        return Math.max(0, Math.min(Math.min(walk, jump), Math.min(descend, costs.swim())));
    }

    private static double upWeight(CostModel costs) {
        double jump = Math.min(costs.walk() + costs.jump(), costs.jump() + costs.place());
        return Math.max(0, Math.min(jump, Math.min(costs.climb(), costs.swim())));
    }

    private static double downWeight(CostModel costs) {
        // Dig-descend and a descending climb/swim can be cheaper than the configured fall cost.
        return Math.max(0, Math.min(Math.min(costs.fall(), costs.walk()), Math.min(costs.climb(), costs.swim())));
    }

    /** The highest standable cell in a downward window, or empty over the void or beneath a low ceiling. */
    public static Optional<Pos> groundBelow(CellView view, int x, int y, int z, int maxDrop) {
        for (int i = 0; i <= maxDrop; i++) {
            Node node = new Node(x, y - i, z);
            if (MoveGenerator.standable(view, node)) {
                return Optional.of(node.pos());
            }
        }
        return Optional.empty();
    }

    /** Projects onto nearby ground, preserving an explicit point if no standable surface exists below it. */
    public static Pos snapDown(CellView view, int x, int y, int z, int maxDrop) {
        return groundBelow(view, x, y, z, maxDrop).orElse(new Pos(x, y, z));
    }
}
