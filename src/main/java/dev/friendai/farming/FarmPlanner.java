package dev.friendai.farming;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Turns a snapshot of a field into an ordered list of steps: what to harvest and replant, what to sow, what to till
 * and where to use bone meal. Pure and deterministic; the executor re-plans when the world disagrees.
 */
public final class FarmPlanner {
    private FarmPlanner() {}

    public static FarmPlan plan(FarmSnapshot snapshot, FarmGoal goal, CropBook crops) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(crops, "crops");
        List<FarmStep> steps = new ArrayList<>();
        for (FieldCell cell : serpentine(ofKind(snapshot, CellKind.RIPE))) {
            steps.add(new FarmStep.Harvest(cell));
            if (cell.seed() != null) {
                steps.add(new FarmStep.Replant(cell, cell.seed()));
            }
        }
        List<FarmStep> capped = steps.size() > goal.maxSteps() ? steps.subList(0, goal.maxSteps()) : steps;
        return new FarmPlan(capped, 0, List.of());
    }

    static List<FieldCell> ofKind(FarmSnapshot snapshot, CellKind kind) {
        return snapshot.cells().stream().filter(cell -> cell.kind() == kind).toList();
    }

    /**
     * Rows by dz ascending; within a row dx ascending on even row positions and descending on odd ones, where the
     * position is the index of the row among the distinct dz values present. dy breaks ties.
     */
    static List<FieldCell> serpentine(List<FieldCell> cells) {
        List<Integer> rows = new ArrayList<>(new TreeSet<>(cells.stream().map(FieldCell::dz).toList()));
        Comparator<FieldCell> order = Comparator.<FieldCell>comparingInt(FieldCell::dz).thenComparing((a, b) -> {
            int dx = Integer.compare(a.dx(), b.dx());
            return rows.indexOf(a.dz()) % 2 == 0 ? dx : -dx;
        }).thenComparingInt(FieldCell::dy);
        return cells.stream().sorted(order).toList();
    }
}
