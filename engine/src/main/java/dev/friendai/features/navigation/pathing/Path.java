package dev.friendai.features.navigation.pathing;

import java.util.List;

/** A planned route: the steps in order, their total cost and how much world the route changes. */
public record Path(List<PathStep> steps, double cost, int breaks, int places, boolean partial) {

    public Path {
        steps = List.copyOf(steps);
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
