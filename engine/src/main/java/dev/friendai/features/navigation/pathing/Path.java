package dev.friendai.features.navigation.pathing;

import java.util.List;
import java.util.Optional;

/** A planned route: the steps in order, their total cost and how much world the route changes. */
public record Path(List<PathStep> steps, double cost, int breaks, int places, boolean partial) {

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
                List<PathStep> rest = steps.subList(i + 1, steps.size());
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
        }
        return this;
    }
}
