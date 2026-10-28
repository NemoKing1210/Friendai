package dev.friendai.agent.autonomy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FreeTimeStatusTest {
    @Test
    void offHasNoLines() {
        assertEquals(FreeTimeStatus.off(), FreeTimeStatus.of(FreeTime.OFF, "waiting", 0, 6, 0));
        assertEquals("Free time: off.", FreeTimeText.statusLine(FreeTimeStatus.off()));
    }

    @Test
    void everyFactHasALine() {
        Shift shift = new Shift(3, "mine iron", List.of(), "gear is missing", 7, 0, 0, false, NeedKind.GEAR);
        ShiftRecord last = new ShiftRecord(2, "stock logs", NeedKind.STOCK, true, "got 16 logs", 0);
        FreeTime state = new FreeTime(
                true, FreeTime.Origin.COMMAND, "", 0, shift, false, 4, List.of(last), List.of(), Map.of(), List.of());
        FreeTimeStatus status = FreeTimeStatus.of(state, "reflex: HUNGER", 3, 6, 2, "planner-x");
        assertEquals("mine iron", status.activity());
        assertEquals("gear is missing", status.why());
        assertEquals("reflex: HUNGER", status.next());
        assertTrue(status.budget().contains("3/6"), status.budget());
        assertTrue(status.budget().contains("backoff 2"), status.budget());
        assertTrue(status.last().contains("stock logs"), status.last());
        assertEquals("planner-x", status.plannerModel());
        String line = FreeTimeText.statusLine(status);
        assertTrue(
                line.contains("mine iron") && line.contains("gear is missing") && line.contains("reflex: HUNGER"),
                line);
        assertTrue(line.contains("stock logs"), line);
        assertTrue(line.contains("planner model: planner-x"), line);
    }

    @Test
    void decisionLabelsNameTheNeed() {
        assertEquals(
                "reflex: SURVIVE",
                FreeTimeText.decisionLabel(
                        new ShiftPolicy.Reflex(new Need(NeedKind.SURVIVE, 100, Executor.REFLEX, ""))));
        assertEquals("director", FreeTimeText.decisionLabel(new ShiftPolicy.Director()));
        assertEquals("waiting for budget", FreeTimeText.decisionLabel(new ShiftPolicy.WaitBudget()));
    }
}
