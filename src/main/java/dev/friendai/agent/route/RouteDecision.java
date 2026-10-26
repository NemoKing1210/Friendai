package dev.friendai.agent.route;

import dev.friendai.agent.tools.ToolGroup;
import java.util.Set;

/**
 * @param groups the tool groups unlocked for the turn (core tools are always visible)
 * @param wantPlanner the planner should draft a plan for this turn
 * @param reason a short note for the debug log
 */
public record RouteDecision(TurnMode mode, Set<ToolGroup> groups, boolean wantPlanner, String reason) {
    public RouteDecision {
        groups = Set.copyOf(groups);
    }
}
