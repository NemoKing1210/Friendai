package dev.friendai.agent.autonomy;

import java.util.List;

/**
 * One stretch of free-time work: a goal the director (or a reflex) chose and the queue plan that carries it out.
 *
 * @param planId the queue plan, or zero while the planner is still thinking
 * @param reflex true when the shift was queued from a template without a model request
 */
public record Shift(
        long id,
        String goal,
        List<String> criteria,
        String why,
        long planId,
        long startedMillis,
        long deadlineMillis,
        boolean reflex,
        NeedKind need) {

    public Shift {
        goal = goal == null ? "" : goal;
        criteria = criteria == null ? List.of() : List.copyOf(criteria);
        why = why == null ? "" : why;
    }

    public Shift withPlan(long plan) {
        return new Shift(id, goal, criteria, why, plan, startedMillis, deadlineMillis, reflex, need);
    }
}
