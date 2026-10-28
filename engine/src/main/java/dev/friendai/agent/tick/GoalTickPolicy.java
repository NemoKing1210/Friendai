package dev.friendai.agent.tick;

import dev.friendai.agent.turn.AgentSettings.Autonomy;

/** Decides when an idle companion with an unfinished goal gets a nudge to carry on. */
public final class GoalTickPolicy {
    private GoalTickPolicy() {}

    /** How much longer than the goal interval an autonomous companion waits before asking what to do next. */
    public static final int IDLE_ASK_FACTOR = 4;

    /**
     * An autonomous companion with no goal and nothing to do may ask its owner a question, never act on its own.
     * Only the {@code AUTONOMOUS} level ever does this.
     */
    public static boolean shouldAskWhatNext(TickFacts f) {
        return f.autonomy() == Autonomy.AUTONOMOUS
                && !f.activeGoal()
                && f.queueIdleOrBlocked()
                && f.ownerOnline()
                && !f.inCombatOrPaused()
                && !f.turnInFlight()
                && f.nowMillis() - f.lastTickMillis() >= f.intervalSec() * 1000L * IDLE_ASK_FACTOR;
    }

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
