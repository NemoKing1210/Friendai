package dev.friendai.features.mining;

import dev.friendai.features.navigation.pathing.Cell;
import dev.friendai.features.navigation.pathing.CellView;
import dev.friendai.features.navigation.pathing.CostModel;
import dev.friendai.features.navigation.pathing.MoveGenerator;
import dev.friendai.features.navigation.pathing.Node;
import dev.friendai.features.navigation.pathing.PathGoal;
import dev.friendai.features.navigation.pathing.PathStep;
import dev.friendai.features.navigation.pathing.Pos;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;

/** Resumable four-edge lookahead over safe cardinal excavation routes. */
public final class ExplorationPlanner {

    public enum Status {
        RUNNING,
        READY,
        NO_ROUTE
    }

    private static final int LOOKAHEAD = 4;
    private static final int[][] HEADINGS = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

    private record Branch(
            Node node, Set<Pos> removed, Set<Long> visited, PathStep first, int firstHeading, int depth, double cost) {}

    private final CellView source;
    private final Node start;
    private final int targetY;
    private final int heading;
    private final MiningBounds bounds;
    private final Set<Pos> supports;
    private final CostModel costs;
    private final int maxNodes;
    private final ArrayDeque<Branch> pending = new ArrayDeque<>();
    private int expanded;
    private Status status = Status.RUNNING;
    private PathStep nextStep;
    private double bestScore = Double.POSITIVE_INFINITY;
    private int bestHeadingRank = Integer.MAX_VALUE;

    public ExplorationPlanner(
            CellView view,
            Node start,
            int targetY,
            int heading,
            MiningBounds bounds,
            Set<Pos> supports,
            CostModel costs,
            int maxNodes) {
        if (heading < 0 || heading > 3 || maxNodes < 1) {
            throw new IllegalArgumentException("heading must be 0..3 and maxNodes must be positive");
        }
        this.source = view;
        this.start = start;
        this.targetY = targetY;
        this.heading = heading;
        this.bounds = bounds;
        this.supports = Set.copyOf(supports);
        this.costs = costs;
        this.maxNodes = maxNodes;
    }

    /** Spends one shared expansion/time budget across every heading and branch. */
    public Status step(long budgetNanos, LongSupplier clock) {
        if (status != Status.RUNNING) {
            return status;
        }
        long begin = clock.getAsLong();
        if (budgetNanos <= 0) {
            return Status.RUNNING;
        }
        if (pending.isEmpty() && expanded == 0) {
            pending.add(new Branch(start, Set.of(), Set.of(start.pack()), null, -1, 0, 0));
        }
        while (!pending.isEmpty() && expanded < maxNodes) {
            if (clock.getAsLong() - begin >= budgetNanos) {
                return Status.RUNNING;
            }
            Branch branch = pending.removeFirst();
            expanded++;
            if (branch.depth() == LOOKAHEAD) {
                continue;
            }
            expand(branch);
        }
        if (!pending.isEmpty()) {
            pending.clear();
        }
        status = nextStep == null ? Status.NO_ROUTE : Status.READY;
        return status;
    }

    public Optional<PathStep> nextStep() {
        return status == Status.READY ? Optional.of(nextStep) : Optional.empty();
    }

    public int expanded() {
        return expanded;
    }

    private void expand(Branch branch) {
        CellView view = projected(branch.removed());
        PathGoal goal = new PathGoal() {
            @Override
            public boolean isGoal(Node node, CellView ignored) {
                return false;
            }

            @Override
            public double heuristic(Node node, CostModel ignored) {
                return 0;
            }

            @Override
            public boolean protects(Pos pos) {
                return supports.contains(pos);
            }
        };
        MoveGenerator moves = new MoveGenerator(view, costs, goal);
        List<PathStep> edges = new ArrayList<>();
        moves.neighbours(branch.node(), 0, 0, 0, edges::add);
        for (PathStep edge : edges) {
            int dy = edge.to().y() - branch.node().y();
            if (dy > 0 || (start.y() <= targetY && dy != 0)) {
                continue;
            }
            MiningPathPolicy policy = new MiningPathPolicy(view, bounds, supports, costs);
            Optional<PathStep> prepared = policy.prepare(view, edge);
            if (prepared.isEmpty()) {
                continue;
            }
            PathStep accepted = prepared.get();
            long key = accepted.to().pack();
            if (branch.visited().contains(key)) {
                continue;
            }
            int edgeHeading = direction(accepted);
            if (edgeHeading < 0) {
                continue;
            }
            int firstHeading = branch.first() == null ? edgeHeading : branch.firstHeading();
            PathStep first = branch.first() == null ? accepted : branch.first();
            Set<Pos> removed = new HashSet<>(branch.removed());
            removed.addAll(accepted.breaks());
            Set<Long> visited = new HashSet<>(branch.visited());
            visited.add(key);
            Branch child = new Branch(
                    accepted.to(),
                    Set.copyOf(removed),
                    Set.copyOf(visited),
                    first,
                    firstHeading,
                    branch.depth() + 1,
                    branch.cost() + accepted.cost());
            consider(child);
            if (child.depth() < LOOKAHEAD) {
                pending.addLast(child);
            }
        }
    }

    private void consider(Branch branch) {
        int changedHeading = branch.firstHeading() == heading ? 0 : 1;
        double headingCost = changedHeading == 0 ? 0 : costs.walk() * 2;
        double progressCost;
        if (start.y() > targetY) {
            int remaining = Math.max(0, branch.node().y() - targetY);
            int overshoot = Math.max(0, targetY - branch.node().y());
            progressCost = remaining * 1000.0 + overshoot * 50.0;
        } else {
            // At or below the hint, prefer a longer safe tunnel before comparing movement costs.
            progressCost = -branch.depth() * 100.0;
        }
        double score = progressCost + branch.cost() + headingCost;
        int rank = branch.firstHeading() == heading ? 0 : branch.firstHeading() + 1;
        if (score < bestScore || (Double.compare(score, bestScore) == 0 && rank < bestHeadingRank)) {
            bestScore = score;
            bestHeadingRank = rank;
            nextStep = branch.first();
        }
    }

    private CellView projected(Set<Pos> removed) {
        return (x, y, z) -> removed.contains(new Pos(x, y, z)) ? Cell.AIR : source.at(x, y, z);
    }

    private static int direction(PathStep step) {
        int dx = step.to().x() - step.from().x();
        int dz = step.to().z() - step.from().z();
        for (int i = 0; i < HEADINGS.length; i++) {
            if (dx == HEADINGS[i][0] && dz == HEADINGS[i][1]) {
                return i;
            }
        }
        return -1;
    }
}
