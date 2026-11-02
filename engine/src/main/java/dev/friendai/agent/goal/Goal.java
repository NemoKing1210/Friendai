package dev.friendai.agent.goal;

import java.util.List;

/**
 * @param planId the linked queue plan, or zero when none
 * @param background true for the up-to-three side goals, false for the one primary goal
 * @param deadlineTick the absolute game tick the goal is due at, or zero when it has no deadline
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
        boolean background,
        long deadlineTick) {
    public Goal {
        criteria = List.copyOf(criteria);
        lessons = List.copyOf(lessons);
        deadlineTick = Math.max(0L, deadlineTick);
    }

    /** A goal without a deadline; zero is the built-in "no deadline", never a fixed date. */
    public Goal(
            long id,
            String text,
            List<Criterion> criteria,
            GoalStatus status,
            long planId,
            int replans,
            List<String> lessons,
            long createdTick,
            boolean background) {
        this(id, text, criteria, status, planId, replans, lessons, createdTick, background, 0L);
    }

    Goal withText(String t) {
        return new Goal(id, t, criteria, status, planId, replans, lessons, createdTick, background, deadlineTick);
    }

    Goal withCriteria(List<Criterion> c) {
        return new Goal(id, text, c, status, planId, replans, lessons, createdTick, background, deadlineTick);
    }

    Goal withStatus(GoalStatus s) {
        return new Goal(id, text, criteria, s, planId, replans, lessons, createdTick, background, deadlineTick);
    }

    Goal withPlan(long p) {
        return new Goal(id, text, criteria, status, p, replans, lessons, createdTick, background, deadlineTick);
    }

    Goal withReplans(int r) {
        return new Goal(id, text, criteria, status, planId, r, lessons, createdTick, background, deadlineTick);
    }

    Goal withLessons(List<String> l) {
        return new Goal(id, text, criteria, status, planId, replans, l, createdTick, background, deadlineTick);
    }

    Goal withDeadline(long tick) {
        return new Goal(id, text, criteria, status, planId, replans, lessons, createdTick, background, tick);
    }
}
