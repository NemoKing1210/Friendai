package dev.friendai.pathing;

import java.util.ArrayList;
import java.util.List;

/**
 * Collapses consecutive plain steps of one straight run into a single leg.
 *
 * <p>The search produces one node per block, which makes the executor re-aim at a new centre every block. A run of
 * {@code WALK}/{@code WALK_DIAGONAL} steps with no breaks or placements and the same direction is one straight line;
 * walking it as one leg lets the steering aim further ahead and saves the per-node stop-and-aim cycle.
 *
 * <p>Every intermediate node must still be standable in the view the smoother is given, so a run that a stale world
 * has closed is left split.
 */
public final class PathSmoother {

    private PathSmoother() {}

    /** A route with each collinear plain run merged; the same instance when nothing can be merged. */
    public static Path simplify(Path path, CellView view) {
        List<PathStep> steps = path.steps();
        if (steps.size() < 2) {
            return path;
        }
        List<PathStep> merged = new ArrayList<>(steps.size());
        int i = 0;
        while (i < steps.size()) {
            PathStep first = steps.get(i);
            if (!plain(first) || !MoveGenerator.standable(view, first.to())) {
                merged.add(first);
                i++;
                continue;
            }
            int j = i;
            double cost = first.cost();
            while (j + 1 < steps.size()) {
                PathStep next = steps.get(j + 1);
                if (!plain(next) || !sameDirection(first, next) || !MoveGenerator.standable(view, next.to())) {
                    break;
                }
                cost += next.cost();
                j++;
            }
            merged.add(new PathStep(first.from(), steps.get(j).to(), first.move(), List.of(), List.of(), cost, 0));
            i = j + 1;
        }
        if (merged.size() == steps.size()) {
            return path;
        }
        double cost = 0.0;
        int breaks = 0;
        int places = 0;
        for (PathStep step : merged) {
            cost += step.cost();
            breaks += step.breaks().size();
            places += step.places().size();
        }
        return new Path(merged, cost, breaks, places, path.partial());
    }

    private static boolean plain(PathStep step) {
        return step.breaks().isEmpty()
                && step.places().isEmpty()
                && (step.move() == Move.WALK || step.move() == Move.WALK_DIAGONAL);
    }

    private static boolean sameDirection(PathStep first, PathStep next) {
        return next.to().x() - next.from().x() == first.to().x() - first.from().x()
                && next.to().z() - next.from().z() == first.to().z() - first.from().z();
    }
}
