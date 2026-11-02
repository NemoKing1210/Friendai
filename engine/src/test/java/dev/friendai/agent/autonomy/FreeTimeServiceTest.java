package dev.friendai.agent.autonomy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FreeTimeServiceTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final Map<UUID, FreeTime> saved = new HashMap<>();
    private final FreeTimeRepository repository = new FreeTimeRepository() {
        @Override
        public FreeTime state(UUID owner) {
            return saved.getOrDefault(owner, FreeTime.OFF);
        }

        @Override
        public void save(UUID owner, FreeTime state) {
            saved.put(owner, state);
        }
    };
    private final FreeTimeService service = new FreeTimeService(repository);

    @Test
    void startStopRoundTrip() {
        assertFalse(service.state(OWNER).on());
        FreeTime on = service.start(OWNER, FreeTime.Origin.COMMAND, "food", 100);
        assertTrue(on.on());
        assertEquals("food", on.wish());
        assertEquals(100, on.sinceMillis());
        FreeTime off = service.stop(OWNER, "owner ended free time", 200);
        assertFalse(off.on());
        assertEquals(200, service.lastEndMillis(OWNER));
    }

    @Test
    void startingAgainOnlyUpdatesTheWish() {
        service.start(OWNER, FreeTime.Origin.IDLE, "", 100);
        FreeTime again = service.start(OWNER, FreeTime.Origin.COMMAND, "iron", 500);
        assertEquals(100, again.sinceMillis());
        assertEquals("iron", again.wish());
        assertEquals(FreeTime.Origin.IDLE, again.origin());
    }

    @Test
    void stopClosesShiftAsFailed() {
        service.start(OWNER, FreeTime.Origin.COMMAND, "", 0);
        service.beginShift(OWNER, "get iron", List.of(), "why", NeedKind.GEAR, false, 10, 1000);
        FreeTime off = service.stop(OWNER, "stopped", 20);
        assertNull(off.shift());
        assertEquals(1, off.history().size());
        assertFalse(off.history().get(0).success());
        assertEquals("stopped", off.history().get(0).summary());
    }

    @Test
    void beginShiftIncrementsIdsAndClearsAwaiting() {
        service.start(OWNER, FreeTime.Origin.COMMAND, "", 0);
        assertTrue(service.awaitChoice(OWNER).awaitingChoice());
        Shift first = service.beginShift(OWNER, "a", List.of("c"), "w", NeedKind.STOCK, false, 1, 2)
                .shift();
        assertFalse(service.state(OWNER).awaitingChoice());
        service.finishShift(OWNER, true, "ok", 3);
        Shift second = service.beginShift(OWNER, "b", List.of(), "", NeedKind.FARM, true, 4, 5)
                .shift();
        assertEquals(first.id() + 1, second.id());
        assertEquals(42, service.linkPlan(OWNER, 42).shift().planId());
    }

    @Test
    void finishShiftAddsHistoryNewestFirst() {
        service.start(OWNER, FreeTime.Origin.COMMAND, "", 0);
        service.beginShift(OWNER, "a", List.of(), "", NeedKind.STOCK, false, 1, 2);
        service.finishShift(OWNER, true, "first", 3);
        service.beginShift(OWNER, "b", List.of(), "", NeedKind.FARM, false, 4, 5);
        FreeTime state = service.finishShift(OWNER, false, "second", 6);
        assertNull(state.shift());
        assertEquals(
                List.of("b", "a"),
                state.history().stream().map(ShiftRecord::goal).toList());
    }

    @Test
    void stateSurvivesNewServiceOverSameBooks() {
        service.start(OWNER, FreeTime.Origin.COMMAND, "wood", 0);
        service.beginShift(OWNER, "a", List.of(), "", NeedKind.STOCK, false, 1, 2);
        service.addLesson(OWNER, "no axe");
        FreeTimeService fresh = new FreeTimeService(repository);
        assertEquals(service.state(OWNER), fresh.state(OWNER));
        assertEquals(List.of("no axe"), fresh.state(OWNER).lessons());
    }

    @Test
    void cooldownStored() {
        service.start(OWNER, FreeTime.Origin.COMMAND, "", 0);
        assertEquals(
                900L,
                service.cooldown(OWNER, NeedKind.UNLOAD, 900).cooldownUntil().get(NeedKind.UNLOAD));
    }

    @Test
    void updateSavesAnyChange() {
        service.update(OWNER, s -> AwaySummary.add(s, "hello"));
        assertEquals(List.of("hello"), saved.get(OWNER).away());
    }

    @Test
    void restoredShiftPlanIsKeptOnlyWhenTheExecutionPlanWasRestored() {
        service.start(OWNER, FreeTime.Origin.COMMAND, "", 0);
        service.beginShift(OWNER, "gather", List.of(), "", NeedKind.FARM, false, 1, 100);
        service.linkPlan(OWNER, 42);

        assertEquals(
                42,
                service.reconcileRestoredPlan(OWNER, Set.of(42L)::contains)
                        .shift()
                        .planId());
        assertNull(
                service.reconcileRestoredPlan(OWNER, Set.<Long>of()::contains).shift());
        assertTrue(service.reconcileRestoredPlan(OWNER, Set.<Long>of()::contains).history().stream()
                .anyMatch(record -> !record.success()));
    }

    @Test
    void restoredShiftWithoutAPlanIsClosedAsOrphaned() {
        service.start(OWNER, FreeTime.Origin.COMMAND, "", 0);
        service.beginShift(OWNER, "gather", List.of(), "", NeedKind.FARM, false, 1, 100);
        FreeTime reconciled = service.reconcileRestoredPlan(OWNER, ignored -> false, 200);

        assertNull(reconciled.shift());
        assertEquals(1, reconciled.history().size());
        assertFalse(reconciled.history().get(0).success());
        assertEquals(
                "the saved work plan was not restored",
                reconciled.history().get(0).summary());
        assertEquals(ShiftStopWhen.WORK_CONFLICTS, reconciled.history().get(0).stopWhen());
        assertEquals(200, reconciled.history().get(0).endedMillis());
    }

    @Test
    void aRunningShiftSurvivesARestartWithItsPurposeAndRemainingBudget() {
        service.start(OWNER, FreeTime.Origin.IDLE, "focus on iron", 600_000);
        service.beginShift(OWNER, "mine iron", List.of(), "", NeedKind.GEAR, false, 600_000, 900_000);
        service.linkPlan(OWNER, 42);

        FreeTimeService restarted = new FreeTimeService(repository);
        Shift restored = restarted.reconcileRestoredPlan(OWNER, Set.of(42L)::contains, 840_000).shift();

        assertNotNull(restored, "a shift whose plan came back is resumed, not dropped");
        assertEquals(42, restored.planId());
        assertEquals("gearing up", restored.purpose());
        assertEquals("5 min", restored.budget());
        assertEquals(ShiftStopWhen.DONE, restored.stopWhen());
        assertEquals(60_000L, restored.deadlineMillis() - 840_000, "only the budget it had left remains");
        assertEquals("focus on iron", restarted.state(OWNER).wish());
        assertTrue(restarted.takePendingReport(OWNER).isEmpty(), "a resumed shift has nothing to report");
    }

    @Test
    void aRestoredShiftWhosePlanIsGoneEndsAsAWorkConflictAndSaysWhy() {
        service.start(OWNER, FreeTime.Origin.IDLE, "", 600_000);
        service.beginShift(OWNER, "mine iron", List.of(), "", NeedKind.GEAR, false, 600_000, 900_000);
        service.linkPlan(OWNER, 42);

        FreeTimeService restarted = new FreeTimeService(repository);
        FreeTime ended = restarted.reconcileRestoredPlan(OWNER, ignored -> false, 840_000);

        assertNull(ended.shift(), "the recorded shift is not resumed honestly");
        ShiftRecord record = ended.history().getFirst();
        assertEquals(ShiftStopWhen.WORK_CONFLICTS, record.stopWhen());
        assertEquals(FreeTimeService.ORPHANED_SHIFT, record.summary());
        assertEquals(
                "Free time: gearing up — the saved work plan was not restored;"
                        + " the work no longer fitted the queue.",
                restarted.takePendingReport(OWNER).orElseThrow());
        assertTrue(restarted.takePendingReport(OWNER).isEmpty(), "the report is said once");
    }

    @Test
    void beginShiftStatesPurposeBudgetAndStopCondition() {
        service.start(OWNER, FreeTime.Origin.COMMAND, "", 0);
        Shift shift = service.beginShift(OWNER, "tend the wheat", List.of(), "", NeedKind.FARM, true, 600_000, 900_000)
                .shift();
        assertEquals("tending the farm", shift.purpose());
        assertEquals("5 min", shift.budget());
        assertEquals(ShiftStopWhen.DONE, shift.stopWhen());
    }

    @Test
    void theStopConditionIsRecordedInHistory() {
        service.start(OWNER, FreeTime.Origin.COMMAND, "", 0);
        service.beginShift(OWNER, "a", List.of(), "", NeedKind.STOCK, false, 1, 2);
        FreeTime finished = service.finishShift(OWNER, false, "the owner paused the work", 3, ShiftStopWhen.OWNER_PAUSED);
        assertEquals(ShiftStopWhen.OWNER_PAUSED, finished.history().get(0).stopWhen());

        service.beginShift(OWNER, "b", List.of(), "", NeedKind.FARM, false, 4, 5);
        FreeTime stopped = service.stop(OWNER, "stopped by command", 6);
        assertEquals(
                ShiftStopWhen.OWNER_SPOKE,
                stopped.history().get(0).stopWhen(),
                "ending free time is the owner speaking");

        service.start(OWNER, FreeTime.Origin.COMMAND, "", 7);
        service.beginShift(OWNER, "c", List.of(), "", NeedKind.GEAR, false, 8, 9);
        assertEquals(
                ShiftStopWhen.BUDGET_SPENT,
                service.finishShift(OWNER, false, ShiftOutcome.TIME_UP, 10, ShiftStopWhen.BUDGET_SPENT)
                        .history()
                        .get(0)
                        .stopWhen());
    }
}
