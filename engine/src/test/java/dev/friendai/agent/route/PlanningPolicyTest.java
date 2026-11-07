package dev.friendai.agent.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.friendai.agent.plan.PlanSubject;
import dev.friendai.execution.queue.PlanStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlanningPolicyTest {
    private static final String ORDER = "build a house and bring iron";

    private final PlanningPolicy policy = new PlanningPolicy();

    private static PlanSignals signals(TurnMode mode, int kinds, boolean complex, boolean planRequest) {
        return new PlanSignals(
                mode,
                ORDER,
                kinds,
                complex,
                planRequest,
                false,
                false,
                false,
                false,
                false,
                false,
                true,
                false,
                0L,
                "",
                5L,
                List.of(),
                0L);
    }

    /** A recovery batch that holds a plan trouble, with the plan live or gone. */
    private static PlanSignals signals(
            TurnMode mode, boolean troubledQueued, boolean troubledLive, boolean budgetSpent) {
        return new PlanSignals(
                mode,
                "",
                0,
                false,
                false,
                false,
                false,
                false,
                troubledQueued,
                troubledLive,
                false,
                true,
                budgetSpent,
                7L,
                "boom",
                0L,
                List.of(),
                0L);
    }

    private static PlanSignals guest(PlanSignals signals) {
        return new PlanSignals(
                signals.mode(),
                signals.playerText(),
                signals.distinctKindsOfWork(),
                signals.complexMarker(),
                signals.explicitPlanRequest(),
                signals.hasActivePrimaryGoal(),
                signals.goalHasNoQueuedWork(),
                signals.goalTickWithUnmet(),
                signals.troubledPlanQueued(),
                signals.troubledPlanLive(),
                signals.freeTimeShift(),
                false,
                signals.budgetSpent(),
                signals.troubledPlanId(),
                signals.failure(),
                signals.goalId(),
                signals.unmet(),
                signals.shiftId());
    }

    private static PlanSignals troubled(long planId, PlanStatus status, int replansLeft, boolean budgetSpent) {
        boolean replannable = status == PlanStatus.ACTIVE && replansLeft > 0 && !budgetSpent;
        return new PlanSignals(
                TurnMode.RECOVERY,
                "",
                0,
                false,
                false,
                false,
                false,
                false,
                replannable,
                replannable,
                false,
                true,
                budgetSpent,
                planId,
                "boom",
                0L,
                List.of(),
                0L);
    }

    private static PlanSignals ownerLineAndTrouble(String text) {
        return new PlanSignals(
                TurnMode.RECOVERY,
                text,
                2,
                false,
                false,
                false,
                false,
                false,
                true,
                true,
                false,
                true,
                false,
                7L,
                "boom",
                0L,
                List.of(),
                0L);
    }

    private static PlanSignals goalTickWithoutQueuedWork(boolean unmet) {
        return new PlanSignals(
                TurnMode.EVENT,
                "",
                0,
                false,
                false,
                true,
                true,
                unmet,
                false,
                false,
                false,
                true,
                false,
                0L,
                "",
                3L,
                List.of("2 logs missing"),
                0L);
    }

    private static PlanSignals budgetSpentTrouble() {
        return troubled(7, PlanStatus.ACTIVE, 2, true);
    }

    private static PlanSignals freeTime(long shiftId) {
        return new PlanSignals(
                TurnMode.EVENT,
                "",
                0,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                true,
                true,
                false,
                0L,
                "",
                0L,
                List.of(),
                shiftId);
    }

    @Test
    void chatNeverPlans() {
        assertEquals(
                PlanNeed.NONE,
                policy.beforeTurn(signals(TurnMode.CHAT, 3, true, false)).need());
        assertEquals(
                "no-plan-occasion",
                policy.beforeTurn(signals(TurnMode.CHAT, 3, true, false)).reason());
        assertNull(policy.beforeTurn(signals(TurnMode.CHAT, 3, true, false)).subject());
    }

    @Test
    void severalKindsOfOwnerWorkPlanTheOrder() {
        PlanningDecision decision = policy.beforeTurn(signals(TurnMode.GOAL, 2, false, false));
        assertEquals(PlanNeed.REQUIRED, decision.need());
        assertEquals(new PlanSubject.OwnerOrder(ORDER), decision.subject());
        assertEquals("several-kinds", decision.reason());
    }

    @Test
    void explicitPlanRequestPlansOneKindOfWork() {
        assertEquals(
                "explicit-plan-request",
                policy.beforeTurn(signals(TurnMode.ACTION, 1, false, true)).reason());
        assertEquals(
                new PlanSubject.OwnerOrder(ORDER),
                policy.beforeTurn(signals(TurnMode.ACTION, 1, false, true)).subject());
    }

    @Test
    void aSimpleActionDoesNotPlanBeforeTheModel() {
        assertEquals(
                PlanNeed.NONE,
                policy.beforeTurn(signals(TurnMode.ACTION, 1, false, false)).need());
    }

    @Test
    void aComplexMarkerPlansAOneKindOrder() {
        PlanningDecision decision = policy.beforeTurn(signals(TurnMode.ACTION, 1, true, false));
        assertEquals(PlanNeed.REQUIRED, decision.need());
        assertEquals(new PlanSubject.OwnerOrder(ORDER), decision.subject());
        assertEquals("complex-order", decision.reason());
    }

    @Test
    void aGuestOrderNeverPlans() {
        assertEquals(
                PlanNeed.NONE,
                policy.beforeTurn(guest(signals(TurnMode.GOAL, 2, true, false))).need());
    }

    @Test
    void aQueuedTroubledPlanIsReplannedEvenForAGuest() {
        assertEquals(
                new PlanSubject.TroubledPlan(7, "boom"),
                policy.beforeTurn(troubled(7, PlanStatus.ACTIVE, 2, false)).subject());
        assertEquals(
                new PlanSubject.TroubledPlan(7, "boom"),
                policy.beforeTurn(guest(troubled(7, PlanStatus.ACTIVE, 2, false)))
                        .subject());
    }

    @Test
    void aCancelledTroubledPlanIsNotPlannedAgain() {
        assertEquals(
                PlanNeed.NONE,
                policy.beforeTurn(signals(TurnMode.RECOVERY, true, false, false))
                        .need());
        assertEquals(
                "no-plan-occasion",
                policy.beforeTurn(signals(TurnMode.RECOVERY, true, false, false))
                        .reason());
    }

    @Test
    void aLiveTroubledPlanIsStillPlanned() {
        assertEquals(
                new PlanSubject.TroubledPlan(7, "boom"),
                policy.beforeTurn(signals(TurnMode.RECOVERY, true, true, false)).subject());
    }

    @Test
    void anOwnerLineWinsOverAPlanTrouble() {
        assertEquals(
                "owner-line-first",
                policy.beforeTurn(ownerLineAndTrouble(ORDER)).reason());
        assertEquals(
                new PlanSubject.OwnerOrder(ORDER),
                policy.beforeTurn(ownerLineAndTrouble(ORDER)).subject());
    }

    @Test
    void aGoalTickOnlyPlansWithUnmetCriteria() {
        assertEquals(
                PlanNeed.NONE,
                policy.beforeTurn(goalTickWithoutQueuedWork(false)).need());
        assertEquals(
                new PlanSubject.ActiveGoal(3, List.of("2 logs missing")),
                policy.beforeTurn(goalTickWithoutQueuedWork(true)).subject());
    }

    @Test
    void aSpentBudgetStopsReplanning() {
        assertEquals(PlanNeed.NONE, policy.beforeTurn(budgetSpentTrouble()).need());
    }

    @Test
    void aFreeTimeShiftPlansItsOwnWork() {
        assertEquals(
                new PlanSubject.FreeTimeShift(9), policy.beforeTurn(freeTime(9)).subject());
    }
}
