package dev.friendai.pathing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * An incremental A* over block positions. Each {@link #step} call expands nodes until its time budget runs out and
 * keeps its state, so a caller on the server thread can spread one search over several ticks.
 *
 * <p>The closed set is keyed by position only: the same place reached with fewer placed blocks is not searched
 * again. That is an accepted approximation; the executor re-plans when it runs out of material.
 */
public final class PathSearch {

    private record Entry(double f, double h, long sequence, Record record) {}

    private static final Comparator<Entry> ORDER = Comparator.comparingDouble(Entry::f)
            .thenComparingDouble(Entry::h)
            .thenComparingLong(Entry::sequence);

    private static final class Record {
        final Node node;
        final double g;
        final int placed;
        final int headUnderwater;
        /** The step that led here; null for the start. */
        final PathStep step;

        Record(Node node, double g, int placed, int headUnderwater, PathStep step) {
            this.node = node;
            this.g = g;
            this.placed = placed;
            this.headUnderwater = headUnderwater;
            this.step = step;
        }
    }

    private final CellView view;
    private final CostModel costs;
    private final PathGoal goal;
    private final Node start;
    private final SearchOptions options;
    private final MoveGenerator moves;

    private final PriorityQueue<Entry> open = new PriorityQueue<>(ORDER);
    private final Map<Long, Record> records = new HashMap<>();
    private final Set<Long> closed = new HashSet<>();
    private final double startHeuristic;

    private long sequence;
    private int expanded;
    private Record best;
    private double bestHeuristic;
    private SearchResult finished;

    public PathSearch(CellView view, CostModel costs, PathGoal goal, Node start, SearchOptions options) {
        this.view = view;
        this.costs = costs;
        this.goal = goal;
        this.start = start;
        this.options = options;
        this.moves = new MoveGenerator(view, costs, goal, options.placePrice());
        this.startHeuristic = goal.heuristic(start, costs);
        this.bestHeuristic = startHeuristic;
        Record root = new Record(start, 0, 0, 0, null);
        records.put(start.pack(), root);
        open.add(new Entry(startHeuristic, startHeuristic, sequence++, root));
    }

    /**
     * Expands nodes until {@code budgetNanos} is used up (or a limit is hit) and reports what the search knows.
     * A budget of 0 expands nothing, so a caller can create the search cheaply and pace it itself.
     */
    public SearchResult step(long budgetNanos, LongSupplier nanoClock) {
        if (finished != null) {
            return finished;
        }
        long begin = nanoClock.getAsLong();
        while (!open.isEmpty() && expanded < options.maxNodes() && nanoClock.getAsLong() - begin < budgetNanos) {
            Entry entry = open.poll();
            Record record = entry.record();
            if (closed.contains(record.node.pack())) {
                continue;
            }
            closed.add(record.node.pack());
            expanded++;
            if (goal.isGoal(record.node, view)) {
                finished = new SearchResult(SearchResult.Status.FOUND, buildPath(record, false));
                return finished;
            }
            double h = goal.heuristic(record.node, costs);
            if (h < bestHeuristic) {
                bestHeuristic = h;
                best = record;
            }
            moves.neighbours(record.node, record.placed, options.placeable(), record.headUnderwater,
                    step -> relax(record, step));
        }
        if (open.isEmpty() || expanded >= options.maxNodes()) {
            finished = finish(expanded >= options.maxNodes());
        }
        return finished == null ? SearchResult.running() : finished;
    }

    /** How many nodes have been expanded so far, across all calls. */
    public int expanded() {
        return expanded;
    }

    private void relax(Record parent, PathStep step) {
        Node to = step.to();
        if (closed.contains(to.pack())) {
            return;
        }
        if (Math.max(Math.abs(to.x() - start.x()), Math.abs(to.z() - start.z())) > options.maxRange()) {
            return;
        }
        int placed = parent.placed + step.places().size();
        if (placed > options.placeable()) {
            return;
        }
        double g = parent.g + step.cost();
        Record existing = records.get(to.pack());
        if (existing != null && existing.g <= g) {
            return;
        }
        Record record = new Record(to, g, placed, step.headUnderwater(), step);
        records.put(to.pack(), record);
        double h = goal.heuristic(to, costs);
        open.add(new Entry(g + h, h, sequence++, record));
    }

    /**
     * An exhausted search means the goal cannot be reached from here. Only a hit limit can return a partial route:
     * the search simply had no time to look further.
     */
    private SearchResult finish(boolean limitHit) {
        if (limitHit && best != null && bestHeuristic < startHeuristic) {
            return new SearchResult(SearchResult.Status.PARTIAL, buildPath(best, true));
        }
        return new SearchResult(SearchResult.Status.NO_PATH, null);
    }

    private Path buildPath(Record end, boolean partial) {
        List<PathStep> steps = new ArrayList<>();
        Record current = end;
        int guard = 0;
        while (current != null && current.step != null && guard++ < 100_000) {
            steps.add(current.step);
            current = records.get(current.step.from().pack());
        }
        Collections.reverse(steps);
        double cost = 0;
        int breaks = 0;
        int places = 0;
        for (PathStep step : steps) {
            cost += step.cost();
            breaks += step.breaks().size();
            places += step.places().size();
        }
        return new Path(steps, cost, breaks, places, partial);
    }
}
