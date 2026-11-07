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
    static final String PLANNER_OFF = "planner-off";
    static final String PLANNER_ALWAYS = "planner-always";

    /** Decides the planning need before the first model request of the turn, with the default eagerness. */
    public PlanningDecision beforeTurn(PlanSignals signals) {
        return beforeTurn(signals, PlannerMode.AUTO);
    }

    /**
     * Decides the planning need under one eagerness setting. {@link PlannerMode#OFF} never asks about any subject;
     * {@link PlannerMode#ALWAYS} also drafts a plan for an actionable owner order — an order turn whose owner text
     * names a recognized kind of work, carries a complexity marker or asks for a plan — while a line with no
     * recognized kind of work and no complexity marker still costs no planner request. An event turn has no player
     * text, so a free-time shift or an unmet goal is decided by the ordinary rules.
     */
    public PlanningDecision beforeTurn(PlanSignals signals, PlannerMode mode) {
        if (mode == PlannerMode.OFF) return PlanningDecision.none(PLANNER_OFF);
        PlanningDecision automatic = automaticDecision(signals);
        if (mode == PlannerMode.ALWAYS && !automatic.required()) {
            PlanningDecision forced = forcedOwnerOrder(signals);
            if (forced != null) return forced;
        }
        return automatic;
    }

    private PlanningDecision automaticDecision(PlanSignals signals) {
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

    /** The plan {@link PlannerMode#ALWAYS} forces for an actionable owner line, or null when there is no work named. */
    private static PlanningDecision forcedOwnerOrder(PlanSignals signals) {
        if (signals == null || signals.mode() == TurnMode.CHAT) return null;
        boolean orderMode = signals.mode() == TurnMode.ACTION
                || signals.mode() == TurnMode.GOAL
                || signals.mode() == TurnMode.RECOVERY;
        boolean namesWork =
                signals.distinctKindsOfWork() > 0 || signals.complexMarker() || signals.explicitPlanRequest();
        if (!orderMode
                || !namesWork
                || !signals.ownerCaller()
                || signals.playerText().isBlank()) return null;
        return new PlanningDecision(
                PlanNeed.REQUIRED, new PlanSubject.OwnerOrder(signals.playerText()), PLANNER_ALWAYS);
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
