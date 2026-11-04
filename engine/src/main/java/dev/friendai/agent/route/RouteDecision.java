package dev.friendai.agent.route;

import dev.friendai.agent.tools.ToolGroup;
import java.util.Set;

/**
 * @param groups the tool groups unlocked for the turn (core tools are always visible)
 * @param planning whether a plan should be drafted for this turn, and for what
 * @param reason a short note for the debug log
 */
public record RouteDecision(TurnMode mode, Set<ToolGroup> groups, PlanningDecision planning, String reason) {
    public RouteDecision {
        groups = Set.copyOf(groups);
        planning = planning == null ? PlanningDecision.none("no-plan-occasion") : planning;
        reason = reason == null ? "" : reason;
    }

    /** Kept as a derived accessor so callers that only ask "plan or not" do not depend on the planning model. */
    public boolean wantPlanner() {
        return planning.need() == PlanNeed.REQUIRED;
    }
}
