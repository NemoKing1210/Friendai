package dev.friendai.agent.goal;

import java.util.List;

/**
 * @param planId the linked queue plan, or zero when none
 * @param background true for the up-to-three side goals, false for the one primary goal
 */
public record Goal(
        long id,
        String text,
        List<Criterion> criteria,
        GoalStatus status,
        long planId,
        int replans,
        List<String> lessons,
        long createdTick,
        boolean background) {
    public Goal {
        criteria = List.copyOf(criteria);
        lessons = List.copyOf(lessons);
    }

    Goal withText(String t) { return new Goal(id, t, criteria, status, planId, replans, lessons, createdTick, background); }
    Goal withCriteria(List<Criterion> c) { return new Goal(id, text, c, status, planId, replans, lessons, createdTick, background); }
    Goal withStatus(GoalStatus s) { return new Goal(id, text, criteria, s, planId, replans, lessons, createdTick, background); }
    Goal withPlan(long p) { return new Goal(id, text, criteria, status, p, replans, lessons, createdTick, background); }
    Goal withReplans(int r) { return new Goal(id, text, criteria, status, planId, r, lessons, createdTick, background); }
    Goal withLessons(List<String> l) { return new Goal(id, text, criteria, status, planId, replans, l, createdTick, background); }
}
