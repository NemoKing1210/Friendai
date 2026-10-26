package dev.friendai.agent.route;

import dev.friendai.brain.TurnKind;

/**
 * What the router may look at.
 *
 * @param playerText the joined player messages of the turn; empty on an event turn
 * @param planBlockedOrFailed the batch holds a failed or blocked plan event
 * @param linkedPlanSteps jobs left in the active goal's plan, or zero
 */
public record RouteSignals(
        TurnKind turn, String playerText, boolean hasActiveGoal, boolean planBlockedOrFailed, int linkedPlanSteps) {
    public RouteSignals {
        playerText = playerText == null ? "" : playerText;
    }
}
