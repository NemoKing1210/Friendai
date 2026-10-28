package dev.friendai.agent.autonomy;

import dev.friendai.agent.turn.AgentSettings.Autonomy;

/**
 * Decides what the companion does when it has nothing to run.
 *
 * <p>Free time is the companion's own work: while it is on, the idle fallback must stand where the companion is
 * instead of walking to the owner. The gap is real — a shift's plan may still be drafted, blocked on a guard, or
 * between activities — and a walk to the owner cancels the work and drops the queue. When free time is off,
 * disabled or passive, or the companion is fighting or doing the owner's own jobs, the old default applies.
 */
public final class DefaultActivityPolicy {
    private DefaultActivityPolicy() {}

    /**
     * @param followModeOn the owner's follow switch: off means never walk to them on the companion's own initiative
     * @param ownerWorkQueued the owner's own jobs wait to run, so free time is not what the companion is doing
     * @param inCombat the companion is fighting, sleeping or in a retreat
     */
    public static boolean shouldFollow(
            FreeTime state,
            AutonomySettings s,
            Autonomy ceiling,
            boolean followModeOn,
            boolean ownerWorkQueued,
            boolean inCombat) {
        if (!followModeOn) {
            return false;
        }
        if (!state.on() || !s.enabled() || ceiling == Autonomy.PASSIVE) {
            return true;
        }
        return ownerWorkQueued || inCombat;
    }
}
