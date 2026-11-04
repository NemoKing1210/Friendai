package dev.friendai.agent.route;

import dev.friendai.agent.turn.TurnKind;
import java.util.List;

/**
 * What the router may look at.
 *
 * @param playerText the joined player messages of the turn; empty on an event turn
 * @param planBlockedOrFailed the batch holds a failed or blocked plan event
 * @param linkedPlanSteps jobs left in the active goal's plan, or zero
 * @param ownerCaller the turn's principal is the owner
 * @param troubledPlanQueued the failed or blocked plan belongs to this companion and may be replanned
 * @param goalUnmet the batch holds a goal tick whose criteria are still unmet
 * @param goalHasNoQueuedWork the active goal has no queued work
 * @param freeTimeShift the batch opens a free-time shift that has no plan
 * @param budgetSpent the replan budget is exhausted
 * @param troubledPlanId the troubled plan's id, or zero
 * @param failure the troubled plan's failure text
 * @param goalId the active goal's id, or zero
 * @param unmet the active goal's unmet criteria
 * @param shiftId the free-time shift's id, or zero
 */
public record RouteSignals(
        TurnKind turn,
        String playerText,
        boolean hasActiveGoal,
        boolean planBlockedOrFailed,
        int linkedPlanSteps,
        boolean ownerCaller,
        boolean troubledPlanQueued,
        boolean goalUnmet,
        boolean goalHasNoQueuedWork,
        boolean freeTimeShift,
        boolean budgetSpent,
        long troubledPlanId,
        String failure,
        long goalId,
        List<String> unmet,
        long shiftId) {
    public RouteSignals {
        playerText = playerText == null ? "" : playerText;
        failure = failure == null ? "" : failure;
        unmet = unmet == null ? List.of() : List.copyOf(unmet);
    }
}
