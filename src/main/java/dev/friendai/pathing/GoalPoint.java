package dev.friendai.pathing;

/** A goal near a single point, as {@code MoveHelper} wants: within a horizontal radius and a height band. */
public record GoalPoint(double x, double y, double z, double horizontal, double vertical)
        implements PathGoal {

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
        return Math.max(0, Math.min(costs.walk(),
                Math.min(costs.diagonal() / Math.sqrt(2.0), (costs.walk() + costs.jump()) / 2.0)));
    }

    private static double upWeight(CostModel costs) {
        return Math.max(0, Math.min(costs.climb(), costs.walk() + costs.jump()));
    }

    private static double downWeight(CostModel costs) {
        return Math.max(0, costs.fall());
    }

    /**
     * The first position at or below {@code (x, y, z)}, at most {@code maxDrop} blocks down, that the companion can
     * stand in: its support holds it up or it is in water. Returns the input when there is none, so a point in
     * mid-air the model handed over never makes the companion build a tower to it.
     */
    public static Pos snapDown(CellView view, int x, int y, int z, int maxDrop) {
        for (int i = 0; i <= maxDrop; i++) {
            int py = y - i;
            Cell cell = view.at(x, py, z);
            if (cell.kind() == Cell.Kind.WATER) {
                return new Pos(x, py, z);
            }
            if (!cell.hazard() && view.at(x, py - 1, z).supports()) {
                return new Pos(x, py, z);
            }
        }
        return new Pos(x, y, z);
    }
}
