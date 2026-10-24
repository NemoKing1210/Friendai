package dev.friendai.pathing;

import java.util.List;
import java.util.function.Consumer;

/**
 * Turns one node into the legal neighbouring steps with their costs. World-changing moves are added in
 * {@link #dig(Node, int, int, Consumer)} and friends; everything here only reads the {@link CellView}.
 */
public final class MoveGenerator {

    private static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] DIAGONALS = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    /** Falls this deep or shallower are always allowed. */
    private static final int MAX_SAFE_FALL = 3;
    /** How far a fall may continue looking for a water landing. */
    private static final int MAX_WATER_DROP = 32;
    /** How many steps in a row the companion may swim with its head under water. */
    public static final int MAX_UNDERWATER_RUN = 8;

    private final CellView view;
    private final CostModel costs;
    private final PathGoal goal;

    public MoveGenerator(CellView view, CostModel costs, PathGoal goal) {
        this.view = view;
        this.costs = costs;
        this.goal = goal;
    }

    /**
     * Whether the companion can occupy {@code n}: foot and head free (or a door it can open), held up by a support
     * below, or swimming in water. Never inside a hazard or an unloaded cell.
     */
    public static boolean standable(CellView view, Node n) {
        Cell foot = view.at(n.pos());
        Cell head = view.at(n.head());
        if (!enterable(foot) || !enterable(head)) {
            return false;
        }
        if (foot.kind() == Cell.Kind.WATER) {
            return true;
        }
        return view.at(n.support()).supports();
    }

    private static boolean enterable(Cell cell) {
        if (cell.hazard()) {
            return false;
        }
        return cell.free() || cell.kind() == Cell.Kind.DOOR;
    }

    public void neighbours(Node from, int placedSoFar, int placeable, int headUnderwater, Consumer<PathStep> out) {
        walkAndJump(from, out);
        ascend(from, out);
        descend(from, out);
        swim(from, headUnderwater, out);
        climb(from, out);
    }

    private void walkAndJump(Node from, Consumer<PathStep> out) {
        for (int[] d : DIRECTIONS) {
            Node to = new Node(from.x() + d[0], from.y(), from.z() + d[1]);
            if (standable(view, to) && view.at(to.pos()).kind() != Cell.Kind.WATER) {
                boolean door = view.at(to.pos()).kind() == Cell.Kind.DOOR
                        || view.at(to.head()).kind() == Cell.Kind.DOOR;
                if (door) {
                    emit(from, to, Move.DOOR, costs.walk() + costs.door(), 0, out);
                } else {
                    emit(from, to, Move.WALK, costs.walk(), 0, out);
                }
            }

            Node landing = new Node(from.x() + 2 * d[0], from.y(), from.z() + 2 * d[1]);
            if (!standable(view, landing) || view.at(landing.pos()).kind() == Cell.Kind.WATER) {
                continue;
            }
            Pos gap = new Pos(from.x() + d[0], from.y(), from.z() + d[1]);
            if (enterable(view.at(gap)) && enterable(view.at(gap.offset(0, 1, 0)))
                    && enterable(view.at(gap.offset(0, 2, 0)))) {
                emit(from, landing, Move.JUMP_GAP, costs.walk() + costs.jump(), 0, out);
            }
        }
        for (int[] d : DIAGONALS) {
            Node to = new Node(from.x() + d[0], from.y(), from.z() + d[1]);
            if (!standable(view, to) || view.at(to.pos()).kind() == Cell.Kind.WATER) {
                continue;
            }
            Pos cornerA = new Pos(from.x() + d[0], from.y(), from.z());
            Pos cornerB = new Pos(from.x(), from.y(), from.z() + d[1]);
            if (!cornerClear(cornerA) || !cornerClear(cornerB)) {
                continue;
            }
            emit(from, to, Move.WALK_DIAGONAL, costs.diagonal(), 0, out);
        }
    }

    private boolean cornerClear(Pos corner) {
        return enterable(view.at(corner)) && enterable(view.at(corner.offset(0, 1, 0)));
    }

    private void ascend(Node from, Consumer<PathStep> out) {
        if (!enterable(view.at(from.pos().offset(0, 2, 0)))) {
            return;
        }
        for (int[] d : DIRECTIONS) {
            Node to = new Node(from.x() + d[0], from.y() + 1, from.z() + d[1]);
            if (standable(view, to)) {
                emit(from, to, Move.ASCEND, costs.walk() + costs.jump(), 0, out);
            }
        }
    }

    private void descend(Node from, Consumer<PathStep> out) {
        for (int[] d : DIRECTIONS) {
            for (int k = 1; k <= MAX_WATER_DROP; k++) {
                Node to = new Node(from.x() + d[0], from.y() - k, from.z() + d[1]);
                if (!enterable(view.at(to.pos())) || !columnClear(to, from)) {
                    break;
                }
                if (!standable(view, to)) {
                    continue;
                }
                boolean water = view.at(to.pos()).kind() == Cell.Kind.WATER;
                if (k <= MAX_SAFE_FALL || water) {
                    emit(from, to, Move.DESCEND, costs.walk() + costs.fall() * k, 0, out);
                }
                if (!water && k >= MAX_SAFE_FALL) {
                    break;
                }
            }
        }
    }

    /** The cells between a landing and the companion's head level must be open to fall through. */
    private boolean columnClear(Node landing, Node from) {
        for (int y = landing.y() + 1; y <= from.y() + 1; y++) {
            if (!enterable(view.at(landing.x(), y, landing.z()))) {
                return false;
            }
        }
        return true;
    }

    private void swim(Node from, int headUnderwater, Consumer<PathStep> out) {
        for (int[] d : DIRECTIONS) {
            swimTo(from, new Node(from.x() + d[0], from.y(), from.z() + d[1]), headUnderwater, out);
        }
        swimTo(from, new Node(from.x(), from.y() + 1, from.z()), headUnderwater, out);
        swimTo(from, new Node(from.x(), from.y() - 1, from.z()), headUnderwater, out);
    }

    private void swimTo(Node from, Node to, int headUnderwater, Consumer<PathStep> out) {
        if (view.at(to.pos()).kind() != Cell.Kind.WATER || !standable(view, to)) {
            return;
        }
        boolean headInWater = view.at(to.head()).kind() == Cell.Kind.WATER;
        if (headInWater && headUnderwater >= MAX_UNDERWATER_RUN) {
            return;
        }
        emit(from, to, Move.SWIM, costs.swim(), headInWater ? headUnderwater + 1 : 0, out);
    }

    private void climb(Node from, Consumer<PathStep> out) {
        if (!onLadderOrVine(from)) {
            return;
        }
        Node up = new Node(from.x(), from.y() + 1, from.z());
        if (standable(view, up)) {
            emit(from, up, Move.CLIMB, costs.climb(), 0, out);
        }
        Node down = new Node(from.x(), from.y() - 1, from.z());
        if (standable(view, down)) {
            emit(from, down, Move.CLIMB, costs.climb(), 0, out);
        }
    }

    private boolean onLadderOrVine(Node node) {
        return view.at(node.pos()).kind() == Cell.Kind.CLIMBABLE
                || view.at(node.head()).kind() == Cell.Kind.CLIMBABLE
                || view.at(node.support()).kind() == Cell.Kind.CLIMBABLE;
    }

    private void emit(Node from, Node to, Move move, double cost, int headUnderwater, Consumer<PathStep> out) {
        emit(from, to, move, cost, headUnderwater, List.of(), List.of(), out);
    }

    private void emit(
            Node from,
            Node to,
            Move move,
            double cost,
            int headUnderwater,
            List<Pos> breaks,
            List<Pos> places,
            Consumer<PathStep> out) {
        if (nextToDanger(to)) {
            cost += costs.lavaNear();
        }
        out.accept(new PathStep(from, to, move, breaks, places, cost, headUnderwater));
    }

    /** Whether standing at {@code node} would put the companion beside lava, cactus or another hazard. */
    private boolean nextToDanger(Node node) {
        for (int[] d : DIRECTIONS) {
            if (dangerous(view.at(node.x() + d[0], node.y(), node.z() + d[1]))
                    || dangerous(view.at(node.x() + d[0], node.y() + 1, node.z() + d[1]))) {
                return true;
            }
        }
        return false;
    }

    private static boolean dangerous(Cell cell) {
        return cell.kind() == Cell.Kind.LAVA || cell.kind() == Cell.Kind.HAZARD;
    }
}
