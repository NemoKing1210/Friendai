package dev.friendai.agent.routine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.friendai.agent.routine.Routine.IntervalTrigger;
import dev.friendai.agent.routine.Routine.Outcome;
import dev.friendai.agent.routine.Routine.Phase;
import dev.friendai.agent.routine.Routine.PhaseTrigger;
import dev.friendai.agent.routine.Routine.Step;
import dev.friendai.agent.routine.Routine.Trigger;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RoutineScheduleTest {
    @Test
    void creationSchedulesTheNextFutureBoundary() {
        assertEquals(
                28_000L,
                RoutineSchedule.nextAfter(new PhaseTrigger(Phase.MORNING), 10_000L, 6_000L),
                "a morning routine created at noon waits for tomorrow");
        assertEquals(
                34_000L,
                RoutineSchedule.nextAfter(new PhaseTrigger(Phase.DAY), 10_000L, 6_000L),
                "a day routine created exactly at its boundary waits a day");
        assertEquals(
                18_000L,
                RoutineSchedule.nextAfter(new PhaseTrigger(Phase.NIGHT), 0L, 0L),
                "night starts 18 000 ticks after morning");
        assertEquals(82_000L, RoutineSchedule.nextAfter(new IntervalTrigger(60), 10_000L, 0L));
    }

    @Test
    void phasesCoverTheDayWithoutGaps() {
        assertEquals(Phase.MORNING, Phase.at(0));
        assertEquals(Phase.MORNING, Phase.at(5_999));
        assertEquals(Phase.DAY, Phase.at(6_000));
        assertEquals(Phase.EVENING, Phase.at(12_000));
        assertEquals(Phase.NIGHT, Phase.at(18_000));
        assertEquals(Phase.MORNING, Phase.at(24_000));
        assertEquals(Phase.NIGHT, Phase.at(-1));
        assertEquals(0L, Phase.NIGHT.end(), "night ends where morning begins");
    }

    @Test
    void dueIsExactAndBackwardTimeNeverFiresEarly() {
        assertFalse(RoutineSchedule.due(999, 1_000));
        assertTrue(RoutineSchedule.due(1_000, 1_000));
        assertTrue(RoutineSchedule.due(1_001, 1_000));
        assertEquals(1_000L, RoutineSchedule.advance(new IntervalTrigger(60), 999, 1_000));
        assertEquals(0L, RoutineSchedule.ticksUntil(2_000, 1_000));
    }

    @Test
    void aMissedWindowIsSkippedInsteadOfReplayed() {
        Trigger phase = new PhaseTrigger(Phase.MORNING);
        assertFalse(RoutineSchedule.missed(phase, 6_999, 1_000));
        assertTrue(RoutineSchedule.missed(phase, 7_000, 1_000));
        assertEquals(25_000L, RoutineSchedule.advance(phase, 7_000, 1_000));

        Trigger interval = new IntervalTrigger(60);
        assertFalse(RoutineSchedule.missed(interval, 72_999, 1_000));
        assertTrue(RoutineSchedule.missed(interval, 73_000, 1_000));
        assertEquals(145_000L, RoutineSchedule.advance(interval, 73_000, 1_000));
    }

    @Test
    void anAdvanceNeverLandsInThePast() {
        Trigger interval = new IntervalTrigger(5);
        long next = RoutineSchedule.advance(interval, 1_000_000L, 1_000L);
        assertTrue(next > 1_000_000L);
        assertEquals(0, (next - 1_000L) % (5 * Routine.TICKS_PER_MINUTE));
    }

    @Test
    void aPhaseIsIdentifiedByItsDayOnTheDayClock() {
        PhaseTrigger morning = new PhaseTrigger(Phase.MORNING);
        RoutineSchedule.Next next = RoutineSchedule.nextPhase(morning, 1_000L, 12_000L);
        assertEquals(1, next.day(), "created in the evening, due the next morning");
        assertEquals(13_000L, next.tick());

        assertTrue(RoutineSchedule.phaseDue(morning, 24_000L, 1), "the window opens at the day boundary");
        assertFalse(RoutineSchedule.phaseDue(morning, 47_000L, 1), "the window belongs to its own day");

        RoutineSchedule.Next after = RoutineSchedule.afterPhase(morning, 25_000L, 24_500L, 1);
        assertEquals(2, after.day(), "the next occurrence is the following day");
        assertEquals(48_500L, after.tick());
    }

    @Test
    void aWindowTheDayClockMovedPastIsMissedNotReplayed() {
        PhaseTrigger morning = new PhaseTrigger(Phase.MORNING);
        assertFalse(RoutineSchedule.phaseMissed(morning, 24_000L, 1), "inside the window");
        assertTrue(RoutineSchedule.phaseMissed(morning, 30_000L, 1), "past the six thousand ticks");
        assertTrue(RoutineSchedule.phaseMissed(morning, 48_000L, 1), "a whole day later");
    }

    @Test
    void sleepDoesNotMoveAPhaseOutOfItsWindow() {
        PhaseTrigger morning = new PhaseTrigger(Phase.MORNING);
        Routine routine = Routine.create("morning", morning, List.of(step("mine_blocks")), 0, 12_000L, 12_000L);
        assertEquals(1, routine.nextDueDay());
        // The day clock jumped to the morning; the game clock only moved by a few ticks.
        assertTrue(
                RoutineSchedule.phaseDue(morning, Routine.DAY_TICKS, routine.nextDueDay()),
                "a morning routine is due after sleeping through the night");
    }

    @Test
    void aRoutineStopsAtItsRunCap() {
        Routine routine =
                Routine.create("morning", new PhaseTrigger(Phase.MORNING), List.of(step("mine_blocks")), 2, 0, 0);
        assertFalse(routine.exhausted());
        routine = new Routine(
                routine.name(),
                routine.trigger(),
                routine.steps(),
                2,
                2,
                true,
                routine.nextDueTick(),
                0,
                0,
                Outcome.NONE,
                "",
                0);
        assertTrue(routine.exhausted());
    }

    @Test
    void routinePayloadRoundTripsBothTriggers() {
        Routine phase = Routine.create(
                "  morning  ",
                new PhaseTrigger(Phase.MORNING),
                List.of(step("mine_blocks"), step("feed_animals")),
                4,
                1_000L,
                0L);
        Routine decoded = RoutineSave.decode(RoutineSave.encode(phase)).orElseThrow();
        assertEquals(phase, decoded);

        Routine interval =
                Routine.create("water", new IntervalTrigger(90), List.of(step("go_to_place")), 0, 1_000L, 0L);
        assertEquals(interval, RoutineSave.decode(RoutineSave.encode(interval)).orElseThrow());
    }

    @Test
    void unknownAndBrokenPayloadsReadAsNoRoutine() {
        assertTrue(RoutineSave.decode(null).isEmpty());
        assertTrue(RoutineSave.decode("").isEmpty());
        assertTrue(RoutineSave.decode("{").isEmpty());
        assertTrue(RoutineSave.decode("{\"name\":\"x\"}").isEmpty());
        assertTrue(RoutineSave.decode("x".repeat(RoutineSave.MAX_INPUT + 1)).isEmpty());
        String valid = RoutineSave.encode(
                Routine.create("morning", new PhaseTrigger(Phase.MORNING), List.of(step("mine_blocks")), 0, 0, 0));
        assertTrue(RoutineSave.decode(valid.replace("MORNING", "NOON")).isEmpty());
        assertTrue(RoutineSave.decode(valid.replace("\"kind\":\"phase\"", "\"kind\":\"other\""))
                .isEmpty());
        assertTrue(RoutineSave.decode(valid.replace("\"tool\":\"mine_blocks\"", "\"tool\":\"Bad Tool\""))
                .isEmpty());
    }

    @Test
    void aMissingOutcomeOrOptionalFieldDefaultsInsteadOfFailing() {
        String minimal = "{\"name\":\"morning\",\"trigger\":{\"kind\":\"interval\",\"minutes\":30},"
                + "\"steps\":[{\"tool\":\"mine_blocks\",\"args\":{}}]}";
        Routine routine = RoutineSave.decode(minimal).orElseThrow();
        assertEquals(Outcome.NONE, routine.lastOutcome());
        assertTrue(routine.enabled());
        assertEquals(0, routine.maxRuns());
        assertEquals("", routine.lastSummary());
    }

    @Test
    void validationRejectsUnboundedOrUnsafeRoutines() {
        assertThrows(
                IllegalArgumentException.class,
                () -> Routine.create("", new PhaseTrigger(Phase.MORNING), List.of(step("mine_blocks")), 0, 0, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> Routine.create(
                        "bad\nname", new PhaseTrigger(Phase.MORNING), List.of(step("mine_blocks")), 0, 0, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> Routine.create("x", new PhaseTrigger(Phase.MORNING), List.of(), 0, 0, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> Routine.create("x", new PhaseTrigger(Phase.MORNING), manySteps(Routine.MAX_STEPS + 1), 0, 0, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> Routine.create(
                        "x",
                        new IntervalTrigger(Routine.MIN_INTERVAL_MINUTES - 1),
                        List.of(step("mine_blocks")),
                        0,
                        0,
                        0));
        assertThrows(
                IllegalArgumentException.class,
                () -> Routine.create(
                        "x",
                        new IntervalTrigger(Routine.MAX_INTERVAL_MINUTES + 1),
                        List.of(step("mine_blocks")),
                        0,
                        0,
                        0));
        assertThrows(
                IllegalArgumentException.class,
                () -> Routine.create(
                        "x",
                        new PhaseTrigger(Phase.MORNING),
                        List.of(new Step("Bad Tool", new JsonObject())),
                        0,
                        0,
                        0));
        assertThrows(
                IllegalArgumentException.class,
                () -> Routine.create(
                        "x",
                        new PhaseTrigger(Phase.MORNING),
                        List.of(step("mine_blocks")),
                        Routine.MAX_RUNS + 1,
                        0,
                        0));
    }

    @Test
    void statusLineIsBoundedAndNamesTheOutcome() {
        Routine routine = new Routine(
                "morning",
                new PhaseTrigger(Phase.MORNING),
                List.of(step("mine_blocks")),
                0,
                3,
                true,
                10_000,
                0,
                0,
                Outcome.SUCCEEDED,
                "two logs",
                0);
        String line = RoutineText.describe(routine, 9_000);
        assertTrue(line.contains("morning"));
        assertTrue(line.contains("next in 1000 ticks"));
        assertTrue(line.contains("last: succeeded"));
        assertTrue(line.length() <= RoutineText.MAX_LINE);
    }

    private static Step step(String tool) {
        return new Step(tool, new JsonObject());
    }

    private static List<Step> manySteps(int count) {
        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            steps.add(step("mine_blocks"));
        }
        return steps;
    }
}
