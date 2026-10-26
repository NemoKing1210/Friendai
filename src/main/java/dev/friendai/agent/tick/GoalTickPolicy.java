package dev.friendai.agent.tick;

import dev.friendai.brain.AgentSettings.Autonomy;

/** Decides when an idle companion with an unfinished goal gets a nudge to carry on. */
public final class GoalTickPolicy {
    private GoalTickPolicy() {}

    /** True only when every condition holds; a passive companion never starts anything by itself. */
    public static boolean shouldTick(TickFacts f) {
        return f.autonomy() != Autonomy.PASSIVE
                && f.activeGoal()
                && f.queueIdleOrBlocked()
                && f.ownerOnline()
                && !f.inCombatOrPaused()
                && !f.turnInFlight()
                && f.nowMillis() - f.lastTickMillis() >= f.intervalSec() * 1000L;
    }
}
