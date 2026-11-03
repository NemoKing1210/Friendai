package dev.friendai.agent.autonomy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import org.junit.jupiter.api.Test;

class ShiftContractTest {
    @Test
    void everyAssessorReasonNamesAPurpose() {
        for (NeedKind kind : NeedKind.values()) {
            assertFalse(ShiftContract.purpose(kind).isBlank(), "no purpose for " + kind);
        }
        assertEquals(ShiftContract.UNNAMED, ShiftContract.purpose(null));
    }

    @Test
    void theBudgetIsTheShiftsOwnMinutes() {
        long now = 1_000L;
        assertEquals("10 min", ShiftContract.budget(now, now + 10 * 60_000L));
        assertEquals("1 min", ShiftContract.budget(now, now + 1));
        assertEquals("3 min", ShiftContract.budget(now, now + 2 * 60_000L + 1));
    }

    @Test
    void aNewShiftStatesItsContract() {
        Shift shift =
                new Shift(1, "get iron", List.of(), "gear is missing", 0, 100, 100 + 5 * 60_000L, false, NeedKind.GEAR);
        assertEquals("gearing up", shift.purpose());
        assertEquals("5 min", shift.budget());
        assertEquals(ShiftStopWhen.DONE, shift.stopWhen());
        assertEquals("gearing up", shift.withPlan(9).purpose(), "linking the plan keeps the contract");
        assertEquals("5 min", shift.withPlan(9).budget());
        assertEquals(ShiftStopWhen.DONE, shift.withPlan(9).stopWhen());
    }

    @Test
    void theStopSetIsClosed() {
        assertEquals(
                List.of(
                        ShiftStopWhen.OWNER_BACK,
                        ShiftStopWhen.OWNER_SPOKE,
                        ShiftStopWhen.BUDGET_SPENT,
                        ShiftStopWhen.WORK_CONFLICTS,
                        ShiftStopWhen.OWNER_PAUSED,
                        ShiftStopWhen.DONE),
                List.of(ShiftStopWhen.values()));
        for (ShiftStopWhen stop : ShiftStopWhen.values()) {
            assertFalse(stop.phrase().isBlank(), "no phrase for " + stop);
        }
    }
}
