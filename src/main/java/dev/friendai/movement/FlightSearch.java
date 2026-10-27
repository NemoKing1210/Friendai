package dev.friendai.movement;

import dev.friendai.pathing.Pos;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Predicate;

/** Incremental, bounded A* over body-clear 3D cells. Paths include both start and goal. */
public final class FlightSearch {
    public enum Status { RUNNING, FOUND, NO_PATH, LIMIT }

    public record Progress(Status status, int expandedThisStep, int totalExpanded) {}

    private record Open(Pos pos, double cost, double score, long order) {}

    private static final int MAX_AXIS_DISTANCE = 64;
    private static final int[][] NEIGHBORS = neighbors();

    private final Pos start;
    private final Pos goal;
    private final Predicate<Pos> free;
    private final int maxExpansions;
    private final PriorityQueue<Open> open = new PriorityQueue<>(Comparator
            .comparingDouble(Open::score).thenComparingLong(Open::order));
    private final Map<Pos, Double> costs = new HashMap<>();
    private final Map<Pos, Pos> previous = new HashMap<>();
    private final Set<Pos> closed = new HashSet<>();
    private List<Pos> path;
    private Status status = Status.RUNNING;
    private int expanded;
    private long order;

    public FlightSearch(Pos start, Pos goal, Predicate<Pos> free, int maxExpansions) {
        if (start == null || goal == null || free == null) throw new NullPointerException();
        if (maxExpansions < 1) throw new IllegalArgumentException("maxExpansions must be positive");
        this.start = start;
        this.goal = goal;
        this.free = free;
        this.maxExpansions = maxExpansions;
        if (!free.test(start) || !free.test(goal) || !withinSearchBounds(goal)) {
            status = Status.NO_PATH;
            return;
        }
        costs.put(start, 0.0);
        open.add(new Open(start, 0.0, heuristic(start), order++));
    }

    public Progress step(int expansionBudget) {
        if (expansionBudget < 1) throw new IllegalArgumentException("expansionBudget must be positive");
        if (status != Status.RUNNING) return new Progress(status, 0, expanded);
        int stepExpansions = 0;
        while (!open.isEmpty() && stepExpansions < expansionBudget) {
            if (expanded >= maxExpansions) {
                status = Status.LIMIT;
                open.clear();
                break;
            }
            Open current = open.poll();
            if (closed.contains(current.pos()) || current.cost() > costs.getOrDefault(current.pos(), Double.POSITIVE_INFINITY)) {
                continue;
            }
            if (current.pos().equals(goal)) {
                path = reconstruct(goal);
                status = Status.FOUND;
                break;
            }
            closed.add(current.pos());
            expanded++;
            stepExpansions++;
            expand(current);
        }
        if (status == Status.RUNNING && open.isEmpty()) status = Status.NO_PATH;
        return new Progress(status, stepExpansions, expanded);
    }

    public Status status() { return status; }

    public List<Pos> path() { return path == null ? null : path; }

    public int expanded() { return expanded; }

    private void expand(Open current) {
        for (int[] delta : NEIGHBORS) {
            Pos next = current.pos().offset(delta[0], delta[1], delta[2]);
            if (closed.contains(next) || !withinSearchBounds(next) || !free.test(next)
                    || !clearDiagonal(current.pos(), delta)) continue;
            double stepCost = Math.sqrt(delta[0] * delta[0] + delta[1] * delta[1] + delta[2] * delta[2]);
            double cost = current.cost() + stepCost;
            if (cost >= costs.getOrDefault(next, Double.POSITIVE_INFINITY)) continue;
            costs.put(next, cost);
            previous.put(next, current.pos());
            open.add(new Open(next, cost, cost + heuristic(next), order++));
        }
    }

    /** Every proper subcell of a diagonal move must be clear, so the body cannot cut corners. */
    private boolean clearDiagonal(Pos from, int[] delta) {
        int axes = (delta[0] == 0 ? 0 : 1) + (delta[1] == 0 ? 0 : 1) + (delta[2] == 0 ? 0 : 1);
        if (axes < 2) return true;
        int endMask = (1 << axes) - 1;
        int[] values = new int[axes];
        int index = 0;
        if (delta[0] != 0) values[index++] = 0;
        if (delta[1] != 0) values[index++] = 1;
        if (delta[2] != 0) values[index] = 2;
        for (int mask = 1; mask < endMask; mask++) {
            int dx = 0, dy = 0, dz = 0;
            for (int bit = 0; bit < axes; bit++) {
                if ((mask & (1 << bit)) == 0) continue;
                switch (values[bit]) {
                    case 0 -> dx = delta[0];
                    case 1 -> dy = delta[1];
                    case 2 -> dz = delta[2];
                    default -> throw new IllegalStateException("unexpected axis");
                }
            }
            if (!free.test(from.offset(dx, dy, dz))) return false;
        }
        return true;
    }

    private boolean withinSearchBounds(Pos pos) {
        return Math.abs((long) pos.x() - start.x()) <= MAX_AXIS_DISTANCE
                && Math.abs((long) pos.y() - start.y()) <= MAX_AXIS_DISTANCE
                && Math.abs((long) pos.z() - start.z()) <= MAX_AXIS_DISTANCE;
    }

    private double heuristic(Pos pos) {
        double dx = (double) goal.x() - pos.x();
        double dy = (double) goal.y() - pos.y();
        double dz = (double) goal.z() - pos.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private List<Pos> reconstruct(Pos end) {
        List<Pos> reversed = new ArrayList<>();
        Pos cursor = end;
        reversed.add(cursor);
        while (!cursor.equals(start)) {
            cursor = previous.get(cursor);
            if (cursor == null) throw new IllegalStateException("flight route has no predecessor");
            reversed.add(cursor);
        }
        Collections.reverse(reversed);
        return List.copyOf(reversed);
    }

    private static int[][] neighbors() {
        List<int[]> result = new ArrayList<>(26);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx != 0 || dy != 0 || dz != 0) result.add(new int[] {dx, dy, dz});
                }
            }
        }
        return result.toArray(int[][]::new);
    }
}
