package dev.friendai.pathing;

/** A goal near a single point, as {@code MoveHelper} wants: within a horizontal radius and a height band. */
public record GoalPoint(double x, double y, double z, double horizontal, double vertical, double walkCost)
        implements PathGoal {

    @Override
    public boolean isGoal(Node n, CellView view) {
        double dx = n.x() + 0.5 - x;
        double dz = n.z() + 0.5 - z;
        return Math.sqrt(dx * dx + dz * dz) <= horizontal && Math.abs(n.y() - y) <= vertical;
    }

    @Override
    public double heuristic(Node n) {
        return Math.max(0, distance(n) - horizontal) * walkCost;
    }

    private double distance(Node n) {
        double dx = n.x() + 0.5 - x;
        double dy = n.y() - y;
        double dz = n.z() + 0.5 - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
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
