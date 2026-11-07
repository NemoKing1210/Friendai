package dev.friendai.agent.turn;

import dev.friendai.agent.plan.PlanSubject;

/**
 * One planner consultation as the durable log sees it.
 *
 * <p>The subject travels as a kind token and an id, never as its text: an owner order or a goal sentence is
 * player-authored and does not belong in a log at the normal level.
 *
 * @param disposition {@code request} when the planner is asked, then {@code delivered}, {@code failed} or
 *     {@code fallback}
 * @param failureCode the plan failure code, or the fallback reason ({@code transport}, {@code parse},
 *     {@code no-input}, {@code no-subject})
 * @param durationMs time spent waiting for the answer, or -1 when the record only announces the request
 * @param planId the queued plan, or 0 when none was queued
 */
public record PlannerAttempt(
        PlanSubject subject, int attempt, String disposition, String failureCode, long durationMs, long planId) {
    public PlannerAttempt {
        attempt = Math.max(1, attempt);
        disposition = disposition == null ? "" : disposition;
        failureCode = failureCode == null ? "" : failureCode;
    }

    /** The subject family, safe to log. */
    public String subjectKind() {
        return kind(subject);
    }

    /** The goal, plan or shift the subject names, or 0 when it names none. */
    public long subjectId() {
        return id(subject);
    }

    public static String kind(PlanSubject subject) {
        if (subject instanceof PlanSubject.OwnerOrder) return "owner_order";
        if (subject instanceof PlanSubject.ActiveGoal) return "active_goal";
        if (subject instanceof PlanSubject.TroubledPlan) return "troubled_plan";
        if (subject instanceof PlanSubject.FreeTimeShift) return "free_time_shift";
        return "unknown";
    }

    public static long id(PlanSubject subject) {
        if (subject instanceof PlanSubject.ActiveGoal goal) return goal.goalId();
        if (subject instanceof PlanSubject.TroubledPlan plan) return plan.planId();
        if (subject instanceof PlanSubject.FreeTimeShift shift) return shift.shiftId();
        return 0L;
    }
}
