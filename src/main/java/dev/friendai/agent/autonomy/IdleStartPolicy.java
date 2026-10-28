package dev.friendai.agent.autonomy;

import dev.friendai.brain.AgentSettings.Autonomy;

/** Decides when a companion left without work starts free time by itself. */
public final class IdleStartPolicy {
    private IdleStartPolicy() {}

    /**
     * @param queueIdle nothing runs and nothing is queued
     * @param lastFreeTimeEndMillis when free time last ended; an idle restart needs a fresh full idle period
     */
    public static boolean shouldStart(
            FreeTime state,
            AutonomySettings s,
            Autonomy ceiling,
            boolean ownerGoalActive,
            boolean queueIdle,
            boolean inCombatOrPaused,
            boolean ownerOnline,
            long nowMillis,
            long lastOwnerActivityMillis,
            long lastFreeTimeEndMillis) {
        if (state.on() || !s.enabled() || !s.idleStart() || ceiling == Autonomy.PASSIVE) {
            return false;
        }
        if (ownerGoalActive || !queueIdle || inCombatOrPaused || (!ownerOnline && !s.whileOffline())) {
            return false;
        }
        long quietSince = Math.max(lastOwnerActivityMillis, lastFreeTimeEndMillis);
        return nowMillis - quietSince >= s.idleMinutes() * 60_000L;
    }
}
