package dev.friendai.features.navigation.pathing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.LongSupplier;

/**
 * An incremental A* over block positions. Each {@link #step} call expands nodes until its time budget runs out and
 * keeps its state, so a caller on the server thread can spread one search over several ticks.
 *
 * <p>Each position keeps the non-dominated states for cost, placed blocks and underwater time, so a cheaper
 * arrival cannot hide a route that conserves the resources needed to finish.
 */
public final class PathSearch {

    /** How many nodes are expanded between two reads of the clock. */
    private static final int CLOCK_INTERVAL = 16;

    private record Entry(double f, double h, long sequence, Record record) {}

    private static final Comparator<Entry> ORDER =
            Comparator.comparingDouble(Entry::f).thenComparingDouble(Entry::h).thenComparingLong(Entry::sequence);

    private static final class Record {
        final Node node;
        final double g;
        final int placed;
        final int headUnderwater;
        /** The step that led here; null for the start. */
        final PathStep step;

        final Record parent;
        boolean closed;
        boolean stale;

        Record(Node node, double g, int placed, int headUnderwater, PathStep step, Record parent) {
            this.node = node;
            this.g = g;
            this.placed = placed;
            this.headUnderwater = headUnderwater;
            this.step = step;
            this.parent = parent;
        }
    }

    private final CellView view;
    private final CostModel costs;
    private final PathGoal goal;
    private final Node start;
    private final SearchOptions options;
    private final PathPolicy policy;
    private final MoveGenerator moves;

    private final PriorityQueue<Entry> open = new PriorityQueue<>(ORDER);
    private final Map<Long, List<Record>> recordsByPos = new HashMap<>();
    private final double startHeuristic;

    private long sequence;
    private int expanded;
    private Record best;
    private double bestHeuristic;
    private SearchResult finished;

    public PathSearch(CellView view, CostModel costs, PathGoal goal, Node start, SearchOptions options) {
        this(view, costs, goal, start, options, PathPolicy.DEFAULT);
    }

    public PathSearch(
            CellView view, CostModel costs, PathGoal goal, Node start, SearchOptions options, PathPolicy policy) {
        this.view = view;
        this.costs = costs;
        this.goal = goal;
        this.start = start;
        this.options = options;
        this.policy = policy;
        this.moves = new MoveGenerator(view, costs, goal, options.placePrice());
        this.startHeuristic = goal.heuristic(start, costs);
        this.bestHeuristic = startHeuristic;
        Record root = new Record(start, 0, 0, 0, null, null);
        recordsByPos.put(start.pack(), new ArrayList<>(List.of(root)));
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
        int sinceClock = 0;
        while (!open.isEmpty() && expanded < options.maxNodes()) {
            // The clock is a system call: read it every few nodes, and always before the first so a budget of 0
            // expands nothing.
            if (sinceClock-- == 0) {
                if (nanoClock.getAsLong() - begin >= budgetNanos) {
                    break;
                }
                sinceClock = CLOCK_INTERVAL - 1;
            }
            Entry entry = open.poll();
            Record record = entry.record();
            if (record.stale || record.closed) {
                continue;
            }
            record.closed = true;
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
            moves.neighbours(
                    record.node,
                    record.placed,
                    options.placeable(),
                    record.headUnderwater,
                    step -> prepareAndRelax(record, step));
        }
        if (open.isEmpty() || expanded >= options.maxNodes()) {
            finished = finish();
        }
        return finished == null ? SearchResult.running() : finished;
    }

    /** How many nodes have been expanded so far, across all calls. */
    public int expanded() {
        return expanded;
    }

    private void prepareAndRelax(Record parent, PathStep step) {
        var prepared = policy.prepare(view, step);
        if (prepared == null || prepared.isEmpty()) {
            return;
        }
        PathStep candidate = prepared.get();
        if (!candidate.from().equals(step.from())
                || !candidate.to().equals(step.to())
                || candidate.move() != step.move()
                || candidate.headUnderwater() != step.headUnderwater()
                || !candidate.places().equals(step.places())
                || !Double.isFinite(candidate.cost())
                || candidate.cost() < step.cost()) {
            return;
        }
        relax(parent, candidate);
    }

    private void relax(Record parent, PathStep step) {
        Node to = step.to();
        if (Math.max(Math.abs(to.x() - start.x()), Math.abs(to.z() - start.z())) > options.maxRange()) {
            return;
        }
        int placed = parent.placed + step.places().size();
        if (placed > options.placeable()) {
            return;
        }
        int underwater = step.headUnderwater();
        double g = parent.g + step.cost();
        List<Record> at = recordsByPos.computeIfAbsent(to.pack(), k -> new ArrayList<>(2));
        for (Record existing : at) {
            if (existing.g <= g && existing.placed <= placed && existing.headUnderwater <= underwater) {
                return;
            }
        }
        at.removeIf(existing -> {
            boolean dominated = g <= existing.g && placed <= existing.placed && underwater <= existing.headUnderwater;
            if (dominated) {
                existing.stale = true;
            }
            return dominated;
        });
        Record record = new Record(to, g, placed, underwater, step, parent);
        at.add(record);
        double h = goal.heuristic(to, costs);
        open.add(new Entry(g + h, h, sequence++, record));
    }

    /** An exhausted or limited search still returns a route when it made progress toward the goal. */
    private SearchResult finish() {
        if (best != null && bestHeuristic < startHeuristic) {
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
            current = current.parent;
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
