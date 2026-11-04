package dev.friendai.agent.route;

import dev.friendai.agent.plan.PlanSubject;

/**
 * The planning half of a routing decision.
 *
 * @param need whether the planner is asked
 * @param subject what to plan; null exactly when {@code need == NONE}
 * @param reason a short stable token for the debug log
 */
public record PlanningDecision(PlanNeed need, PlanSubject subject, String reason) {
    public PlanningDecision {
        need = need == null ? PlanNeed.NONE : need;
        reason = reason == null ? "" : reason;
    }

    /** No plan for this occasion. */
    public static PlanningDecision none(String reason) {
        return new PlanningDecision(PlanNeed.NONE, null, reason);
    }

    public boolean required() {
        return need == PlanNeed.REQUIRED;
    }
}
