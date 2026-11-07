package dev.friendai.agent.route;

import dev.friendai.agent.plan.PlanSubject;

/**
 * Decides, without a model call, whether this occasion deserves a plan and what it is about. The rules are one
 * ordered table so a new occasion is a new {@link PlanSubject} variant and one branch here, never a second
 * condition inside the turn loop.
 *
 * <p>The policy never decides authority: it may ask for an owner order or a goal plan only when the facts say the
 * caller is the owner, and it never asks for a plan on a chat turn.
 */
public final class PlanningPolicy {
    static final String NO_OCCASION = "no-plan-occasion";
    static final String OWNER_LINE_FIRST = "owner-line-first";
    static final String EXPLICIT_PLAN_REQUEST = "explicit-plan-request";
    static final String SEVERAL_KINDS = "several-kinds";
    static final String COMPLEX_ORDER = "complex-order";
    static final String PLAN_TROUBLE = "plan-trouble";
    static final String GOAL_UNMET = "goal-unmet";
    static final String FREE_TIME_SHIFT = "free-time-shift";

    /** Decides the planning need before the first model request of the turn. */
    public PlanningDecision beforeTurn(PlanSignals signals) {
        if (signals == null || signals.mode() == TurnMode.CHAT) return PlanningDecision.none(NO_OCCASION);
        boolean ownerLine = isPlanWorthyOwnerLine(signals);
        if (ownerLine) {
            String reason = signals.mode() == TurnMode.RECOVERY && troubled(signals)
                    ? OWNER_LINE_FIRST
                    : ownerLineReason(signals);
            return new PlanningDecision(PlanNeed.REQUIRED, new PlanSubject.OwnerOrder(signals.playerText()), reason);
        }
        if (signals.mode() == TurnMode.RECOVERY && troubled(signals)) {
            return new PlanningDecision(
                    PlanNeed.REQUIRED,
                    new PlanSubject.TroubledPlan(signals.troubledPlanId(), signals.failure()),
                    PLAN_TROUBLE);
        }
        if (signals.mode() == TurnMode.EVENT
                && signals.goalTickWithUnmet()
                && signals.goalHasNoQueuedWork()
                && signals.ownerCaller()
                && !signals.budgetSpent()) {
            return new PlanningDecision(
                    PlanNeed.REQUIRED, new PlanSubject.ActiveGoal(signals.goalId(), signals.unmet()), GOAL_UNMET);
        }
        if (signals.freeTimeShift() && !signals.budgetSpent()) {
            return new PlanningDecision(
                    PlanNeed.REQUIRED, new PlanSubject.FreeTimeShift(signals.shiftId()), FREE_TIME_SHIFT);
        }
        return PlanningDecision.none(NO_OCCASION);
    }

    /** The free-time driver's entry: a shift with no plan and budget left asks for one. */
    public PlanningDecision forShift(PlanSignals signals) {
        if (signals != null && signals.freeTimeShift() && !signals.budgetSpent()) {
            return new PlanningDecision(
                    PlanNeed.REQUIRED, new PlanSubject.FreeTimeShift(signals.shiftId()), FREE_TIME_SHIFT);
        }
        return PlanningDecision.none(NO_OCCASION);
    }

    private static boolean troubled(PlanSignals signals) {
        return signals.troubledPlanQueued() && !signals.budgetSpent();
    }

    private static boolean isPlanWorthyOwnerLine(PlanSignals signals) {
        boolean orderMode = signals.mode() == TurnMode.ACTION
                || signals.mode() == TurnMode.GOAL
                || signals.mode() == TurnMode.RECOVERY;
        return orderMode
                && signals.ownerCaller()
                && !signals.playerText().isBlank()
                && (signals.explicitPlanRequest()
                        || signals.distinctKindsOfWork() >= TurnRouter.GOAL_SCORE
                        || signals.complexMarker());
    }

    private static String ownerLineReason(PlanSignals signals) {
        if (signals.explicitPlanRequest()) return EXPLICIT_PLAN_REQUEST;
        if (signals.distinctKindsOfWork() >= TurnRouter.GOAL_SCORE) return SEVERAL_KINDS;
        return COMPLEX_ORDER;
    }
}
