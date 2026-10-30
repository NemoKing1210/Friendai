package dev.friendai.features.navigation.pathing;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Consumer;

/**
 * Turns one node into the legal neighbouring steps with their costs: walking, falling, swimming and climbing, plus
 * the world-changing moves that dig, pillar and bridge.
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
    /** Price multiplier of a swim step that ends with the head under water. */
    public static final double SUBMERGED_FACTOR = 1.3;

    private final CellView view;
    private final CostModel costs;
    private final PathGoal goal;
    private final double placePrice;
    private final boolean runningJumps;

    public MoveGenerator(CellView view, CostModel costs, PathGoal goal) {
        this(view, costs, goal, 1);
    }

    public MoveGenerator(CellView view, CostModel costs, PathGoal goal, double placePrice) {
        this(view, costs, goal, placePrice, true);
    }

    /**
     * @param runningJumps whether the body can sprint into its jumps. One without the food for a run-up still clears
     *     a narrow gap with a walking jump, but wider crossings are not offered to it and it is routed around them.
     */
    public MoveGenerator(CellView view, CostModel costs, PathGoal goal, double placePrice, boolean runningJumps) {
        this.view = view;
        this.costs = costs;
        this.goal = goal;
        this.placePrice = placePrice;
        this.runningJumps = runningJumps;
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
        diagonalAscend(from, out);
        descend(from, out);
        diagonalDescend(from, out);
        swim(from, headUnderwater, out);
        climb(from, out);
        dig(from, out);
        digAscend(from, out);
        digDescend(from, out);
        pillar(from, placedSoFar, placeable, out);
        bridge(from, placedSoFar, placeable, out);
    }

    private void walkAndJump(Node from, Consumer<PathStep> out) {
        boolean swimming = view.at(from.pos()).kind() == Cell.Kind.WATER;
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

            if (!swimming) {
                leaps(from, d, out);
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

    /**
     * Emits a jump over every gap a body can clear, landing from one block higher to three lower, as {@link
     * LeapPhysics} works out for its take-off ability. A leap is only offered where the body flies through a real gap
     * and lands on dry footing; over walkable cells the steps of a walk are faster, and water counts as a gap because
     * swimming through it costs more than the jump.
     */
    private void leaps(Node from, int[] d, Consumer<PathStep> out) {
        int openBelow = LeapPhysics.MAX_DROP;
        boolean roofClear = true;
        for (int width = 1; width <= LeapPhysics.MAX_WIDTH; width++) {
            int x = from.x() + width * d[0];
            int z = from.z() + width * d[1];
            if (!gapColumn(x, from.y(), z)) {
                return;
            }
            openBelow = Math.min(openBelow, openBelow(x, from.y(), z, openBelow));
            roofClear &= enterable(view.at(x, from.y() + 3, z));
            for (int dy = LeapPhysics.MAX_RISE; dy >= -openBelow; dy--) {
                if (!LeapPhysics.reachable(width, dy, runningJumps) || (dy > 0 && !roofClear)) {
                    continue;
                }
                Node landing = new Node(x + d[0], from.y() + dy, z + d[1]);
                if (!standable(view, landing)
                        || view.at(landing.pos()).kind() == Cell.Kind.WATER
                        || !openAbove(landing, from.y() + 2)) {
                    continue;
                }
                emit(from, landing, Move.JUMP_GAP, jumpCost(width, dy), 0, out);
            }
        }
    }

    /** Whether the body flies through this column: nothing to stand on at the take-off level and room to rise. */
    private boolean gapColumn(int x, int y, int z) {
        Node cell = new Node(x, y, z);
        boolean walkable = standable(view, cell) && view.at(cell.pos()).kind() != Cell.Kind.WATER;
        return !walkable
                && enterable(view.at(x, y, z))
                && enterable(view.at(x, y + 1, z))
                && enterable(view.at(x, y + 2, z));
    }

    /** How many cells right below the take-off level the column leaves open, at most {@code limit}. */
    private int openBelow(int x, int y, int z, int limit) {
        int open = 0;
        while (open < limit && enterable(view.at(x, y - 1 - open, z))) {
            open++;
        }
        return open;
    }

    /** Whether the cells over a landing lower than the take-off are open up to the height the body flew in at. */
    private boolean openAbove(Node landing, int top) {
        for (int y = landing.y() + 2; y <= top; y++) {
            if (!enterable(view.at(landing.x(), y, landing.z()))) {
                return false;
            }
        }
        return true;
    }

    /** A leap covers {@code width + 1} cells, so it is priced as that much walking plus the jump and any fall. */
    private double jumpCost(int width, int dy) {
        double cost = costs.walk() * (width + 1) + costs.jump();
        return dy < 0 ? cost + costs.fall() * -dy : cost;
    }

    private void ascend(Node from, Consumer<PathStep> out) {
        // A climbable column already has the cheaper, physically executable CLIMB move. Treating it as a lateral
        // jump lets A* leave a ladder or scaffold while rising, which a mob cannot reliably perform.
        if (onLadderOrVine(from)) {
            return;
        }
        if (!enterable(view.at(from.pos().offset(0, 2, 0)))) {
            return;
        }
        for (int[] d : DIRECTIONS) {
            Node to = new Node(from.x() + d[0], from.y() + 1, from.z() + d[1]);
            if (standable(view, to) && view.at(to.pos()).kind() != Cell.Kind.WATER) {
                // Rising inside water is a swim, priced and counted as one; a jump out of it is for dry land.
                emit(from, to, Move.ASCEND, costs.walk() + costs.jump(), 0, out);
            }
        }
    }

    private void diagonalAscend(Node from, Consumer<PathStep> out) {
        if (!enterable(view.at(from.pos().offset(0, 2, 0)))) {
            return;
        }
        for (int[] d : DIAGONALS) {
            Node to = new Node(from.x() + d[0], from.y() + 1, from.z() + d[1]);
            if (standable(view, to) && view.at(to.pos()).kind() != Cell.Kind.WATER && cornersClear(from, d)) {
                emit(from, to, Move.ASCEND, costs.walk() + costs.jump(), 0, out);
            }
        }
    }

    private boolean cornersClear(Node from, int[] d) {
        return cornerClear(new Pos(from.x() + d[0], from.y(), from.z()))
                && cornerClear(new Pos(from.x(), from.y(), from.z() + d[1]));
    }

    private void descend(Node from, Consumer<PathStep> out) {
        for (int[] d : DIRECTIONS) {
            fall(from, d, out);
        }
    }

    private void diagonalDescend(Node from, Consumer<PathStep> out) {
        for (int[] d : DIAGONALS) {
            if (cornersClear(from, d)) {
                fall(from, d, out);
            }
        }
    }

    /**
     * Steps off the edge toward {@code d} and falls down the column beside the companion. The cells between the
     * landing and the head level must be open to fall through; the column above the first landing is checked once,
     * because every deeper landing was only reached through a cell already found open.
     */
    private void fall(Node from, int[] d, Consumer<PathStep> out) {
        int x = from.x() + d[0];
        int z = from.z() + d[1];
        if (!enterable(view.at(x, from.y(), z)) || !enterable(view.at(x, from.y() + 1, z))) {
            return;
        }
        boolean fromWater = view.at(from.pos()).kind() == Cell.Kind.WATER;
        for (int k = 1; k <= MAX_WATER_DROP; k++) {
            Node to = new Node(x, from.y() - k, z);
            if (!enterable(view.at(to.pos()))) {
                return;
            }
            if (!standable(view, to)) {
                continue;
            }
            boolean water = view.at(to.pos()).kind() == Cell.Kind.WATER;
            if (!water) {
                if (k <= MAX_SAFE_FALL) {
                    emit(from, to, Move.DESCEND, costs.walk() + costs.fall() * k, 0, out);
                }
                if (k >= MAX_SAFE_FALL) {
                    return;
                }
                continue;
            }
            // Water is entered once, at its surface, and only from dry ground: every move below that surface is a
            // swim, so the search cannot dive under a lake at fall prices instead of swimming across it.
            if (!fromWater) {
                emit(from, to, Move.DESCEND, costs.walk() + costs.fall() * k, 0, out);
            }
            return;
        }
    }

    private void swim(Node from, int headUnderwater, Consumer<PathStep> out) {
        // A swimming mob moves in three dimensions: a diagonal dip or rise is one stroke, so the surface is
        // reachable from a shore cell without an extra lateral swim, and priced by its length.
        for (int[] d : DIRECTIONS) {
            swimTo(from, new Node(from.x() + d[0], from.y(), from.z() + d[1]), headUnderwater, out);
            for (int dy = -1; dy <= 1; dy += 2) {
                swimTo(
                        from,
                        new Node(from.x() + d[0], from.y() + dy, from.z() + d[1]),
                        headUnderwater,
                        costs.swim() * Math.sqrt(2.0),
                        out);
            }
        }
        for (int[] d : DIAGONALS) {
            if (!cornersClear(from, d)) {
                continue;
            }
            swimTo(
                    from,
                    new Node(from.x() + d[0], from.y(), from.z() + d[1]),
                    headUnderwater,
                    costs.swim() * Math.sqrt(2.0),
                    out);
            for (int dy = -1; dy <= 1; dy += 2) {
                swimTo(
                        from,
                        new Node(from.x() + d[0], from.y() + dy, from.z() + d[1]),
                        headUnderwater,
                        costs.swim() * Math.sqrt(3.0),
                        out);
            }
        }
        swimTo(from, new Node(from.x(), from.y() + 1, from.z()), headUnderwater, out);
        swimTo(from, new Node(from.x(), from.y() - 1, from.z()), headUnderwater, out);
    }

    private void swimTo(Node from, Node to, int headUnderwater, Consumer<PathStep> out) {
        swimTo(from, to, headUnderwater, costs.swim(), out);
    }

    private void swimTo(Node from, Node to, int headUnderwater, double cost, Consumer<PathStep> out) {
        if (view.at(to.pos()).kind() != Cell.Kind.WATER || !standable(view, to)) {
            return;
        }
        boolean headInWater = view.at(to.head()).kind() == Cell.Kind.WATER;
        if (headInWater && headUnderwater >= MAX_UNDERWATER_RUN) {
            return;
        }
        // Swimming with the head under is slower and harder to steer than floating along the surface, and it
        // spends breath, so a route on the surface wins a tie.
        emit(
                from,
                to,
                Move.SWIM,
                headInWater ? cost * SUBMERGED_FACTOR : cost,
                headInWater ? headUnderwater + 1 : 0,
                out);
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

    // --- world-changing moves -----------------------------------------------------------------------------------

    /** Dig the cells blocking a horizontal neighbour, then walk in. Breaks are listed head first. */
    private void dig(Node from, Consumer<PathStep> out) {
        for (int[] d : DIRECTIONS) {
            Node to = new Node(from.x() + d[0], from.y(), from.z() + d[1]);
            if (standable(view, to)) {
                continue;
            }
            List<Pos> blocking = new ArrayList<>();
            if (!enterable(view.at(to.head()))) {
                blocking.add(to.head());
            }
            if (!enterable(view.at(to.pos()))) {
                blocking.add(to.pos());
            }
            emitDig(from, to, Move.DIG, costs.walk(), blocking, out);
        }
    }

    /** Dig the cells above the head and over the neighbour, then step up. */
    private void digAscend(Node from, Consumer<PathStep> out) {
        for (int[] d : DIRECTIONS) {
            Node to = new Node(from.x() + d[0], from.y() + 1, from.z() + d[1]);
            if (standable(view, to)) {
                continue;
            }
            List<Pos> blocking = new ArrayList<>();
            Pos aboveHead = from.pos().offset(0, 2, 0);
            if (!enterable(view.at(aboveHead))) {
                blocking.add(aboveHead);
            }
            if (!enterable(view.at(to.head()))) {
                blocking.add(to.head());
            }
            if (!enterable(view.at(to.pos()))) {
                blocking.add(to.pos());
            }
            emitDig(from, to, Move.DIG_ASCEND, costs.walk() + costs.jump(), blocking, out);
        }
    }

    /** Dig a stair one block down into a solid hillside. */
    private void digDescend(Node from, Consumer<PathStep> out) {
        for (int[] d : DIRECTIONS) {
            Node to = new Node(from.x() + d[0], from.y() - 1, from.z() + d[1]);
            if (standable(view, to)) {
                continue;
            }
            List<Pos> blocking = new ArrayList<>();
            if (!enterable(view.at(to.head()))) {
                blocking.add(to.head());
            }
            if (!enterable(view.at(to.pos()))) {
                blocking.add(to.pos());
            }
            emitDig(from, to, Move.DIG_DESCEND, costs.walk(), blocking, out);
        }
    }

    private void emitDig(Node from, Node to, Move move, double walkCost, List<Pos> blocking, Consumer<PathStep> out) {
        if (blocking.isEmpty()) {
            return;
        }
        List<Pos> breaks = withFalls(blocking);
        if (!canBreakAll(breaks, from)) {
            return;
        }
        if (!standsAfter(to, breaks, List.of())) {
            return;
        }
        emit(from, to, move, walkCost + breakCost(breaks), 0, breaks, List.of(), out);
    }

    /** Jump and place a block under itself; breaking up to two cells above first. */
    private void pillar(Node from, int placedSoFar, int placeable, Consumer<PathStep> out) {
        if (placedSoFar + 1 > placeable) {
            return;
        }
        Pos under = from.pos();
        if (!view.at(under).placeable()) {
            return;
        }
        List<Pos> blocking = new ArrayList<>();
        for (int i = 1; i <= 2; i++) {
            Pos p = under.offset(0, i, 0);
            if (!enterable(view.at(p))) {
                blocking.add(p);
            }
        }
        List<Pos> breaks = withFalls(blocking);
        if (!canBreakAll(breaks, from)) {
            return;
        }
        Node to = new Node(from.x(), from.y() + 1, from.z());
        if (!standsAfter(to, breaks, List.of(under))) {
            return;
        }
        double cost = costs.jump() + costs.place() + placePrice + breakCost(breaks);
        emit(from, to, Move.PILLAR, cost, 0, breaks, List.of(under), out);
    }

    /** Place a block in the support cell of a horizontal neighbour and walk onto it. */
    private void bridge(Node from, int placedSoFar, int placeable, Consumer<PathStep> out) {
        if (placedSoFar + 1 > placeable) {
            return;
        }
        // The companion stands somewhere, so its support is either solid or a block an earlier step placed; the
        // static view still reads a placed cell as air, hence the placeable check.
        Cell under = view.at(from.support());
        if (!supportsStanding(under) && !under.placeable()) {
            return;
        }
        for (int[] d : DIRECTIONS) {
            Node to = new Node(from.x() + d[0], from.y(), from.z() + d[1]);
            if (standable(view, to)) {
                continue;
            }
            if (!enterable(view.at(to.pos())) || !enterable(view.at(to.head()))) {
                continue;
            }
            Pos support = to.support();
            if (!view.at(support).placeable()) {
                continue;
            }
            if (!standsAfter(to, List.of(), List.of(support))) {
                continue;
            }
            double cost = costs.walk() + costs.place() + placePrice;
            emit(from, to, Move.BRIDGE, cost, 0, List.of(), List.of(support), out);
        }
    }

    private static boolean supportsStanding(Cell cell) {
        return cell.kind() == Cell.Kind.SOLID || cell.kind() == Cell.Kind.PARTIAL;
    }

    /** Whether {@code to} can be stood in once {@code breaks} are gone and {@code places} are filled. */
    private boolean standsAfter(Node to, List<Pos> breaks, List<Pos> places) {
        boolean footOk = enterable(view.at(to.pos())) || breaks.contains(to.pos());
        boolean headOk = enterable(view.at(to.head())) || breaks.contains(to.head());
        boolean supported =
                places.contains(to.support()) || view.at(to.support()).supports();
        boolean water = view.at(to.pos()).kind() == Cell.Kind.WATER && !breaks.contains(to.pos());
        return footOk && headOk && (supported || water);
    }

    /** Adds the falling blocks a dig brings down on top of it. */
    private List<Pos> withFalls(List<Pos> blocking) {
        if (blocking.isEmpty()) {
            return blocking;
        }
        LinkedHashSet<Pos> all = new LinkedHashSet<>(blocking);
        for (Pos p : blocking) {
            Pos above = p.offset(0, 1, 0);
            while (view.at(above).falls() && view.at(above).breakable()) {
                all.add(above);
                above = above.offset(0, 1, 0);
            }
        }
        return new ArrayList<>(all);
    }

    private boolean canBreakAll(List<Pos> cells, Node from) {
        for (Pos p : cells) {
            if (!mayBreak(p, from)) {
                return false;
            }
        }
        return true;
    }

    private boolean mayBreak(Pos p, Node from) {
        if (!view.at(p).breakable()) {
            return false;
        }
        if (goal.protects(p)) {
            return false;
        }
        if (p.equals(from.pos()) || p.equals(from.support())) {
            return false;
        }
        for (int[] d : DIRECTIONS) {
            for (int dy = -1; dy <= 1; dy++) {
                Pos neighbour = p.offset(d[0], dy, d[1]);
                if (view.at(neighbour).kind() != Cell.Kind.LAVA) {
                    continue;
                }
                if (!neighbour.equals(from.pos()) && !neighbour.equals(from.head())) {
                    return false;
                }
            }
        }
        Pos above = p.offset(0, 1, 0);
        Pos below = p.offset(0, -1, 0);
        return view.at(above).kind() != Cell.Kind.LAVA && view.at(below).kind() != Cell.Kind.LAVA;
    }

    private double breakCost(List<Pos> cells) {
        double cost = 0;
        for (Pos p : cells) {
            Cell cell = view.at(p);
            cost += Math.max(0, cell.breakTicks()) + costs.breakExtra();
            if (cell.falls()) {
                cost += costs.fallingExtra();
            }
            if (nextToWater(p)) {
                cost += costs.waterNearBreak();
            }
        }
        return cost;
    }

    private boolean nextToWater(Pos p) {
        for (int[] d : DIRECTIONS) {
            if (view.at(p.offset(d[0], 0, d[1])).kind() == Cell.Kind.WATER) {
                return true;
            }
        }
        return false;
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
