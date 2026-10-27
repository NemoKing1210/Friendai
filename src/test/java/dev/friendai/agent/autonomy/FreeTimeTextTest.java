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
        var state = new FreeTime(true, FreeTime.Origin.COMMAND, "", 60_000, shift, false, 2, List.of(), List.of(),
                Map.of(), List.of());
        assertEquals("Free time: on for 12 min; current activity: get iron",
                FreeTimeText.stateLine(state, 60_000 + 12 * 60_000));
    }

    @Test
    void onBetweenShifts() {
        var state = new FreeTime(true, FreeTime.Origin.IDLE, "", 0, null, false, 2, List.of(), List.of(), Map.of(),
                List.of());
        assertEquals("Free time: on for 0 min; between activities", FreeTimeText.stateLine(state, 30_000));
    }

    @Test
    void recentShiftsAreShortLines() {
        var state = new FreeTime(true, FreeTime.Origin.IDLE, "", 0, null, false, 3,
                List.of(new ShiftRecord(2, "smelt iron", NeedKind.PROCESS, false, "no fuel", 9),
                        new ShiftRecord(1, "chop wood", NeedKind.STOCK, true, "16 logs", 5)),
                List.of(), Map.of(), List.of());
        assertEquals(List.of("smelt iron: failed, no fuel", "chop wood: ok, 16 logs"), FreeTimeText.recent(state));
    }
}
