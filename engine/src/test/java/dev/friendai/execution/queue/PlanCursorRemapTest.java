package dev.friendai.execution.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlanCursorRemapTest {
    @Test
    void insertingBeforeTheNextStepShiftsTheFrameIndex() {
        PlanCursor cursor = new PlanCursor(
                List.of(new PlanCursor.Frame(List.of(), 2, "", "", false)), Map.of(), Map.of(), 0, 0, "", null);
        PlanCursor shifted = PlanCursorRemap.afterInsert(cursor, List.of(), 1, 2);
        assertEquals(4, shifted.stack().getFirst().nextIndex());
    }

    @Test
    void insertingBeforeANestedNodeShiftsItsRoutePair() {
        PlanCursor cursor = new PlanCursor(
                List.of(new PlanCursor.Frame(List.of(1, 2), 0, "step 2 → else", "step 2", false)),
                Map.of("step 2", true),
                Map.of(),
                0,
                0,
                "",
                null);
        PlanCursor shifted = PlanCursorRemap.afterInsert(cursor, List.of(), 0, 1);
        assertEquals(List.of(2, 2), shifted.stack().getFirst().route());
        assertEquals("step 3 → else", shifted.stack().getFirst().pathPrefix());
        assertEquals(Map.of("step 3", true), shifted.selectedBranches());
    }

    @Test
    void insertingAfterTheCursorLeavesItAlone() {
        PlanCursor cursor = new PlanCursor(
                List.of(new PlanCursor.Frame(List.of(), 1, "", "", false)), Map.of(), Map.of(), 0, 0, "", null);
        PlanCursor shifted = PlanCursorRemap.afterInsert(cursor, List.of(), 3, 1);
        assertEquals(1, shifted.stack().getFirst().nextIndex());
    }

    @Test
    void aPendingActionIsNeverSilentlyRemapped() {
        PlanCursor cursor = new PlanCursor(
                List.of(new PlanCursor.Frame(List.of(), 1, "", "", false)),
                Map.of(),
                Map.of(),
                0,
                0,
                "step 1",
                new QueuedStep(
                        "wait",
                        new com.google.gson.JsonObject(),
                        null,
                        List.of(),
                        GuardAction.WAIT,
                        null,
                        1,
                        List.of()));
        try {
            PlanCursorRemap.afterInsert(cursor, List.of(), 0, 1);
            throw new AssertionError("expected refusal");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
