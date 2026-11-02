package dev.friendai.agent.autonomy;

import java.util.List;

/**
 * One stretch of free-time work: a goal the director (or a reflex) chose and the queue plan that carries it out.
 *
 * @param planId the queue plan, or zero while the planner is still thinking
 * @param reflex true when the shift was queued from a template without a model request
 * @param purpose one short phrase from the assessor's reasons: what this shift is for
 * @param budget what the shift may spend, in the counters free time already keeps
 * @param stopWhen the condition that ends it by itself; an owner action may end it earlier
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
        NeedKind need,
        String purpose,
        String budget,
        ShiftStopWhen stopWhen) {

    public Shift(
            long id,
            String goal,
            List<String> criteria,
            String why,
            long planId,
            long startedMillis,
            long deadlineMillis,
            boolean reflex,
            NeedKind need) {
        this(
                id,
                goal,
                criteria,
                why,
                planId,
                startedMillis,
                deadlineMillis,
                reflex,
                need,
                ShiftContract.purpose(need),
                ShiftContract.budget(startedMillis, deadlineMillis),
                ShiftStopWhen.DONE);
    }

    public Shift {
        goal = goal == null ? "" : goal;
        criteria = criteria == null ? List.of() : List.copyOf(criteria);
        why = why == null ? "" : why;
        purpose = purpose == null || purpose.isBlank() ? ShiftContract.purpose(need) : purpose.strip();
        budget = budget == null || budget.isBlank()
                ? ShiftContract.budget(startedMillis, deadlineMillis)
                : budget.strip();
        stopWhen = stopWhen == null ? ShiftStopWhen.DONE : stopWhen;
    }

    public Shift withPlan(long plan) {
        return new Shift(
                id, goal, criteria, why, plan, startedMillis, deadlineMillis, reflex, need, purpose, budget, stopWhen);
    }
}
