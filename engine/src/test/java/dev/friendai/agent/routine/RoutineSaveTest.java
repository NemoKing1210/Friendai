package dev.friendai.agent.routine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.agent.routine.Routine.IntervalTrigger;
import dev.friendai.agent.routine.Routine.Outcome;
import dev.friendai.agent.routine.Routine.Step;
import java.util.List;
import org.junit.jupiter.api.Test;

class RoutineSaveTest {
    private static Routine routine(boolean report) {
        return Routine.create(
                "morning",
                new IntervalTrigger(5),
                List.of(new Step("mine_block_at", new com.google.gson.JsonObject())),
                0,
                1_000,
                1_000,
                report);
    }

    @Test
    void roundTripsTheReportChoiceAndTheReportedBlocker() {
        Routine saved = routine(true).withBlocker("failed # mine_block_at: blocked");

        Routine loaded = RoutineSave.decode(RoutineSave.encode(saved)).orElseThrow();

        assertEquals(saved, loaded);
        assertTrue(loaded.report(), "the owner's report choice survives a restart");
        assertEquals("failed # mine_block_at: blocked", loaded.lastBlocker());
    }

    @Test
    void anOlderRecordReadsQuietAndWithoutABlocker() {
        String older = "{\"name\":\"morning\",\"trigger\":{\"kind\":\"interval\",\"minutes\":5},"
                + "\"steps\":[{\"tool\":\"mine_block_at\",\"args\":{}}],\"maxRuns\":0,\"runs\":0,"
                + "\"enabled\":true,\"nextDue\":1700,\"nextDueDay\":0,\"lastStarted\":0,\"lastPlan\":0,"
                + "\"outcome\":\"SUCCEEDED\",\"summary\":\"done\"}";

        Routine loaded = RoutineSave.decode(older).orElseThrow();

        assertFalse(loaded.report(), "a routine written before the choice existed does not start reporting");
        assertEquals("", loaded.lastBlocker());
        assertEquals(Outcome.SUCCEEDED, loaded.lastOutcome());
    }

    @Test
    void everyTransitionCarriesTheChoiceAndTheBlocker() {
        Routine base = routine(true).withBlocker("a cause");
        RoutineSchedule.Next next = new RoutineSchedule.Next(9_000, 0);

        for (Routine moved : List.of(
                base.withNextDue(next),
                base.started(2_000, next, 4),
                base.withPlan(4),
                base.finished(Outcome.FAILED, "boom", next),
                base.skipped(next, "late"),
                base.withEnabled(false),
                base.withOutcome(Outcome.SUCCEEDED, "ok"))) {
            assertTrue(moved.report(), "the report choice must survive a transition");
            assertEquals("a cause", moved.lastBlocker(), "the reported blocker must survive a transition");
        }
    }

    @Test
    void aSuccessCanClearTheBlockerSoTheNextFailureIsHeard() {
        Routine cleared = routine(false).withBlocker("a cause").withBlocker("");

        assertEquals("", cleared.lastBlocker());
    }

    @Test
    void theBlockerKeyErasesNumbersSoOneCauseStaysOne() {
        assertEquals(
                Routine.blockerKey("3 steps, 1 failed; failed #12 mine_block_at: the block is protected"),
                Routine.blockerKey("3 steps, 1 failed; failed #45 mine_block_at: the block is protected"));
        assertEquals(
                "",
                Routine.blockerKey(""),
                "a missing reason must not collide with a real one through a stray separator");
    }
}
