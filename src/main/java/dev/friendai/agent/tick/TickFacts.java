package dev.friendai.agent.tick;

import dev.friendai.brain.AgentSettings.Autonomy;

/**
 * Everything the initiative policy looks at, read once per check by the server adapter.
 *
 * @param activeGoal the owner has an active (not blocked, not finished) primary goal
 * @param queueIdleOrBlocked nothing is running and no plan is making progress on its own
 * @param inCombatOrPaused combat, a paused or suspended runner, sleeping, or a long expedition
 * @param turnInFlight the brain is already talking to the model
 */
public record TickFacts(
        boolean activeGoal,
        boolean queueIdleOrBlocked,
        boolean ownerOnline,
        boolean inCombatOrPaused,
        boolean turnInFlight,
        long nowMillis,
        long lastTickMillis,
        Autonomy autonomy,
        int intervalSec) {}
