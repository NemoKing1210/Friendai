package dev.friendai.agent.autonomy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FreeTimeTextTest {
    @Test
    void offSaysNothing() {
        assertEquals("", FreeTimeText.stateLine(FreeTime.OFF, 0));
    }

    @Test
    void onNamesTheActivity() {
        var shift = new Shift(1, "get iron", List.of(), "", 3, 0, 0, false, NeedKind.GEAR);
        var state = new FreeTime(
                true, FreeTime.Origin.COMMAND, "", 60_000, shift, false, 2, List.of(), List.of(), Map.of(), List.of());
        assertEquals(
                "Free time: on for 12 min; current activity: get iron"
                        + " (purpose: gearing up; budget: 1 min; stops when the work finished)",
                FreeTimeText.stateLine(state, 60_000 + 12 * 60_000));
    }

    @Test
    void aShiftThatTheOwnerEndedNamesTheStop() {
        var state = new FreeTime(
                true,
                FreeTime.Origin.IDLE,
                "",
                0,
                null,
                false,
                3,
                List.of(
                        new ShiftRecord(
                                2, "gather", NeedKind.FARM, false, "the owner paused the work", 9,
                                ShiftStopWhen.OWNER_PAUSED),
                        new ShiftRecord(1, "chop wood", NeedKind.STOCK, true, "16 logs", 5)),
                List.of(),
                Map.of(),
                List.of());
        assertEquals(
                List.of("gather: failed, the owner paused the work [OWNER_PAUSED]", "chop wood: ok, 16 logs"),
                FreeTimeText.recent(state));
    }

    @Test
    void onBetweenShifts() {
        var state = new FreeTime(
                true, FreeTime.Origin.IDLE, "", 0, null, false, 2, List.of(), List.of(), Map.of(), List.of());
        assertEquals("Free time: on for 0 min; between activities", FreeTimeText.stateLine(state, 30_000));
    }

    @Test
    void theEndReportNamesThePurposeWhatItGotDoneAndTheStopCondition() {
        var shift = new Shift(1, "mine iron", List.of(), "", 42, 1000, 301_000, false, NeedKind.GEAR);
        assertEquals(
                "Free time: gearing up — mined 6 iron ore; the work finished.",
                FreeTimeText.endReport(shift, true, "mined 6 iron ore", ShiftStopWhen.DONE));
        assertEquals(
                "Free time: gearing up — the queue already holds mine_blocks iron_ore;"
                        + " the work no longer fitted the queue.",
                FreeTimeText.endReport(
                        shift, false, "the queue already holds mine_blocks iron_ore", ShiftStopWhen.WORK_CONFLICTS));
        assertEquals(
                "Free time: gearing up — ran out of time; the shift's budget ran out.",
                FreeTimeText.endReport(shift, false, "ran out of time", ShiftStopWhen.BUDGET_SPENT));
    }

    @Test
    void aReportDoesNotRepeatTheStopWhenTheReasonIsTheSame() {
        var shift = new Shift(1, "mine iron", List.of(), "", 42, 1000, 301_000, false, NeedKind.GEAR);
        assertEquals(
                "Free time: gearing up — the owner came back.",
                FreeTimeText.endReport(shift, false, "the owner came back", ShiftStopWhen.OWNER_BACK));
        assertEquals(
                "Free time: gearing up — done; the work finished.",
                FreeTimeText.endReport(shift, true, "", ShiftStopWhen.DONE));
        assertEquals(
                "Free time: gearing up — nothing came of it; the work finished.",
                FreeTimeText.endReport(shift, false, "", ShiftStopWhen.DONE));
    }

    @Test
    void recentShiftsAreShortLines() {
        var state = new FreeTime(
                true,
                FreeTime.Origin.IDLE,
                "",
                0,
                null,
                false,
                3,
                List.of(
                        new ShiftRecord(2, "smelt iron", NeedKind.PROCESS, false, "no fuel", 9),
                        new ShiftRecord(1, "chop wood", NeedKind.STOCK, true, "16 logs", 5)),
                List.of(),
                Map.of(),
                List.of());
        assertEquals(List.of("smelt iron: failed, no fuel", "chop wood: ok, 16 logs"), FreeTimeText.recent(state));
    }
}
