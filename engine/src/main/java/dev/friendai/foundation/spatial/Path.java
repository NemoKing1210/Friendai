package dev.friendai.foundation.spatial;

import java.util.List;
import java.util.Optional;

/** A planned route: the steps in order, their total cost and how much world the route changes. */
public record Path(List<PathStep> steps, double cost, int breaks, int places, boolean partial) {

    /**
     * How far a walker's cell may stand from the line of a step and still count as walking it. A diagonal step gains
     * a row and a column at once, so the cell the feet occupy halfway across it lies {@code sqrt(2) / 2} from the
     * line, which still counts; a cell one block to the side of a plain leg does not.
     */
    public static final double ROUTE_CORRIDOR = 0.75;

    public Path {
        steps = List.copyOf(steps);
    }

    /**
     * What may be walked of this route. A route that reaches the goal is one complete passage and is returned whole.
     *
     * <p>A partial route is what a search returns when a limit or the material budget stops it short of the goal; a
     * partial route that places a block is a stair or a bridge cut in half. Walking it spends the block and strands
     * the companion in front of a rise it cannot climb, so the caller must plan the whole passage or gather for it
     * instead. Only the steps before the first placement may be walked; empty when the very first step places, and
     * the route is refused rather than half-built.
     */
    public Optional<Path> passagePrefix() {
        if (!partial) {
            return Optional.of(this);
        }
        int stop = 0;
        while (stop < steps.size() && steps.get(stop).places().isEmpty()) {
            stop++;
        }
        if (stop == 0) {
            return Optional.empty();
        }
        if (stop == steps.size()) {
            return Optional.of(this);
        }
        double prefixCost = 0;
        int prefixBreaks = 0;
        for (int i = 0; i < stop; i++) {
            prefixCost += steps.get(i).cost();
            prefixBreaks += steps.get(i).breaks().size();
        }
        return Optional.of(new Path(steps.subList(0, stop), prefixCost, prefixBreaks, 0, true));
    }

    /** The node the route ends at; null for an empty route. */
    public Node end() {
        return steps.isEmpty() ? null : steps.get(steps.size() - 1).to();
    }

    /**
     * The route without the leading steps the walker has already covered. A search runs on a snapshot and the
     * companion keeps walking meanwhile, so its new route may begin a few nodes behind where it stands; walking back
     * to the route's first node would be a visible stutter. Looks only at the first {@code lookahead} steps and
     * returns this route when {@code here} is not the end of one of them.
     */
    public Path afterNode(Node here, int lookahead) {
        for (int i = Math.min(lookahead, steps.size()) - 1; i >= 0; i--) {
            if (steps.get(i).to().equals(here)) {
                return drop(i + 1);
            }
        }
        return this;
    }

    /**
     * The route without the steps the walker has already passed. Unlike {@link #afterNode}, this also covers the
     * walker standing in the middle of a leg, which happens whenever a leg spans more than one block after the route
     * is smoothed: the projection of {@code current} onto the first {@code lookahead} legs picks the leg it is on,
     * that leg is the active step and is kept whole, and only the legs before it are dropped.
     *
     * <p>Only a leg within {@link #ROUTE_CORRIDOR} can match, and the first such leg in route order wins, so the trim
     * never skips a step the walker has not covered; a position on a leg's end node counts that leg as walked and
     * makes the next leg active, as {@code afterNode} does. Returns this route when no leg within the lookahead holds
     * the position, so a walker that is off the route is not rewarded with a shortcut.
     */
    public Path afterPosition(Pos current, int lookahead) {
        int limit = Math.min(lookahead, steps.size());
        for (int i = 0; i < limit; i++) {
            PathStep step = steps.get(i);
            if (offRouteSquared(step, current) > ROUTE_CORRIDOR * ROUTE_CORRIDOR) {
                continue;
            }
            int first = along(step, current) >= 1.0 ? i + 1 : i;
            return first == 0 ? this : drop(first);
        }
        return this;
    }

    /** The route from step {@code first} on, with the cost and the world changes of the dropped steps gone too. */
    private Path drop(int first) {
        List<PathStep> rest = steps.subList(first, steps.size());
        double restCost = 0;
        int restBreaks = 0;
        int restPlaces = 0;
        for (PathStep step : rest) {
            restCost += step.cost();
            restBreaks += step.breaks().size();
            restPlaces += step.places().size();
        }
        return new Path(rest, restCost, restBreaks, restPlaces, partial);
    }

    /** Where {@code current} falls on the leg of {@code step}: 0 at its start, 1 at its end, clamped between them. */
    private static double along(PathStep step, Pos current) {
        double dx = step.to().x() - step.from().x();
        double dy = step.to().y() - step.from().y();
        double dz = step.to().z() - step.from().z();
        double lengthSquared = dx * dx + dy * dy + dz * dz;
        if (lengthSquared == 0) {
            return 0;
        }
        double ox = current.x() - step.from().x();
        double oy = current.y() - step.from().y();
        double oz = current.z() - step.from().z();
        return Math.clamp((ox * dx + oy * dy + oz * dz) / lengthSquared, 0, 1);
    }

    /** Squared distance from {@code current} to the nearest point of the leg of {@code step}. */
    private static double offRouteSquared(PathStep step, Pos current) {
        double along = along(step, current);
        double dx = current.x()
                - (step.from().x() + along * (step.to().x() - step.from().x()));
        double dy = current.y()
                - (step.from().y() + along * (step.to().y() - step.from().y()));
        double dz = current.z()
                - (step.from().z() + along * (step.to().z() - step.from().z()));
        return dx * dx + dy * dy + dz * dz;
    }
}
