package dev.friendai.agent.route;

import java.util.List;

/**
 * The facts {@link PlanningPolicy} decides on. The mode is already resolved by {@link TurnRouter}, so the policy
 * never re-derives the question or advice branches; the subject ids travel here because the decision copies them
 * into a {@link dev.friendai.agent.plan.PlanSubject}.
 *
 * @param mode the turn mode the router chose
 * @param playerText the joined player messages of the turn, never null
 * @param distinctKindsOfWork how many unrelated kinds of work the player's verbs name
 * @param complexMarker the vocabulary recognized an order that usually needs several steps
 * @param explicitPlanRequest the player asked for a plan
 * @param hasActivePrimaryGoal the owner already has a primary goal; it never suppresses an owner order
 * @param goalHasNoQueuedWork the active goal has no plan or the plan has no pending work
 * @param goalTickWithUnmet the batch holds a goal tick whose criteria are still unmet
 * @param troubledPlanQueued the batch holds a failed or blocked plan this companion may replan
 * @param freeTimeShift the batch opens a free-time shift that has no plan
 * @param ownerCaller the turn's principal is the owner
 * @param budgetSpent the replan budget is exhausted
 * @param troubledPlanId the troubled plan's id, or zero
 * @param failure the troubled plan's failure text, never null
 * @param goalId the active goal's id, or zero
 * @param unmet the active goal's unmet criteria, never null
 * @param shiftId the free-time shift's id, or zero
 */
public record PlanSignals(
        TurnMode mode,
        String playerText,
        int distinctKindsOfWork,
        boolean complexMarker,
        boolean explicitPlanRequest,
        boolean hasActivePrimaryGoal,
        boolean goalHasNoQueuedWork,
        boolean goalTickWithUnmet,
        boolean troubledPlanQueued,
        boolean freeTimeShift,
        boolean ownerCaller,
        boolean budgetSpent,
        long troubledPlanId,
        String failure,
        long goalId,
        List<String> unmet,
        long shiftId) {
    public PlanSignals {
        playerText = playerText == null ? "" : playerText;
        failure = failure == null ? "" : failure;
        unmet = unmet == null ? List.of() : List.copyOf(unmet);
    }
}
