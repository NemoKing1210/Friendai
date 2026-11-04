package dev.friendai.agent.plan;

import java.util.HashMap;
import java.util.Map;

/**
 * A runtime-only replan budget for plans that have no goal, whose replans would otherwise be unbounded. It is never
 * persisted: a restart forgets the attempts, exactly as it forgets the planless plan's history.
 */
public final class ReplanBudget {
    /** Tracked plans before the oldest state is dropped wholesale; a plan that never settles cannot grow the map. */
    public static final int MAX_TRACKED_PLANS = 64;

    private final Map<Long, Integer> attempts = new HashMap<>();

    /** True when the plan has already used {@code max} replans. */
    public boolean exhausted(long planId, int max) {
        return attempts.getOrDefault(planId, 0) >= Math.max(0, max);
    }

    /** Spends one replan for the plan. */
    public void attempt(long planId) {
        if (attempts.size() >= MAX_TRACKED_PLANS && !attempts.containsKey(planId)) attempts.clear();
        attempts.merge(planId, 1, Integer::sum);
    }

    /** The plan finished or was cancelled; a later plan with the same id starts fresh. */
    public void settled(long planId) {
        attempts.remove(planId);
    }

    int tracked() {
        return attempts.size();
    }
}
