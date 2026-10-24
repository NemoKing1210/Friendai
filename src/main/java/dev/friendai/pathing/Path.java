package dev.friendai.pathing;

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
}
