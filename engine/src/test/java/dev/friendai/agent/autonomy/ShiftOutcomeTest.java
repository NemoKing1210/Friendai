package dev.friendai.agent.autonomy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ShiftOutcomeTest {
    private static final AutonomySettings S = AutonomySettings.DEFAULTS;
    private static final Shift SHIFT = new Shift(4, "mine iron", List.of(), "", 9, 0, 0, false, NeedKind.GEAR);

    @Test
    void successRecordsWithoutLesson() {
        ShiftOutcome.Result r = ShiftOutcome.of(SHIFT, true, "mined 9 iron ore", "STONE", "STONE", "", 1000, S);
        assertTrue(r.record().success());
        assertEquals("mined 9 iron ore", r.record().summary());
        assertTrue(r.lesson().isEmpty());
        assertTrue(r.cooldownUntil().isEmpty());
        assertTrue(r.notable().isEmpty());
    }

    @Test
    void stageChangeIsNotable() {
        ShiftOutcome.Result r = ShiftOutcome.of(SHIFT, true, "crafted an iron pickaxe", "STONE", "IRON", "", 0, S);
        assertEquals("IRON", r.notable().orElseThrow());
    }

    @Test
    void failureAddsLessonAndCooldown() {
        ShiftOutcome.Result r = ShiftOutcome.of(
                SHIFT, false, "no pickaxe", "STONE", "STONE", "missing tool: craft a stone pickaxe first", 1000, S);
        assertFalse(r.record().success());
        assertEquals(
                "mine iron: missing tool: craft a stone pickaxe first",
                r.lesson().orElseThrow());
        assertEquals(1000 + 10 * 60_000L, r.cooldownUntil().orElseThrow());
    }

    @Test
    void failureWithoutDiagnosisUsesTheSummary() {
        ShiftOutcome.Result r = ShiftOutcome.of(SHIFT, false, "storage is full", "STONE", "STONE", "", 0, S);
        assertEquals("mine iron: storage is full", r.lesson().orElseThrow());
    }

    @Test
    void timeLimitIsNotAFailure() {
        ShiftOutcome.Result r = ShiftOutcome.of(SHIFT, false, "ran out of time", "STONE", "STONE", "", 0, S);
        assertFalse(r.record().success());
        assertTrue(r.lesson().isEmpty());
        assertTrue(r.cooldownUntil().isEmpty());
    }

    @Test
    void reflexFailureCoolsDownWithoutALesson() {
        Shift reflex = new Shift(5, "unload", List.of(), "", 3, 0, 0, true, NeedKind.UNLOAD);
        ShiftOutcome.Result r = ShiftOutcome.of(reflex, false, "storage is full", "STONE", "STONE", "", 0, S);
        assertTrue(r.lesson().isEmpty(), "a template failing teaches the director nothing");
        assertTrue(r.cooldownUntil().isPresent());
    }

    @Test
    void lessonIsTruncated() {
        ShiftOutcome.Result r = ShiftOutcome.of(SHIFT, false, "x".repeat(400), "STONE", "STONE", "", 0, S);
        assertEquals(FreeTime.MAX_LESSON, r.lesson().orElseThrow().length());
    }
}
