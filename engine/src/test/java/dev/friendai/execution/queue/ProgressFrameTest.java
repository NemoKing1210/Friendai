package dev.friendai.execution.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Progress guards measure one work item's own time and travel, never the world's age or a shared meter. */
class ProgressFrameTest {
    private static final class Facts implements GuardFacts {
        long now = 150;
        long odometer = 3600;
        long elapsed = 99_999;
        int travelled = 99_999;

        @Override
        public int countItems(String idOrTag) {
            return 0;
        }

        @Override
        public int emptySlots() {
            return 0;
        }

        @Override
        public float healthFraction() {
            return 1;
        }

        @Override
        public int ownerDistance() {
            return 0;
        }

        @Override
        public int countBlocks(String idOrTag, int radius) {
            return 0;
        }

        @Override
        public String dimension() {
            return "minecraft:overworld";
        }

        @Override
        public long timeElapsed() {
            return elapsed;
        }

        @Override
        public int distanceTravelled() {
            return travelled;
        }

        @Override
        public long simulationTick() {
            return now;
        }

        @Override
        public long travelledMilliBlocks() {
            return odometer;
        }
    }

    private static QueuedStep step(String tool, Guard... guards) {
        return new QueuedStep(tool, new JsonObject(), null, List.of(guards), GuardAction.WAIT, null, 1, List.of());
    }

    private static Job onlyJobOf(JobQueue queue, long planId) {
        return queue.jobsOfPlan(planId).getFirst();
    }

    @Test
    void twoPlansMeasureElapsedTimeFromTheirOwnStart() {
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        Facts facts = new Facts();
        long early = queue.addPlan("early", List.of(step("wait", new Guard.TimeElapsed(100))), PlanOptions.DEFAULT)
                .planId();
        for (int i = 0; i < 150; i++) queue.advanceClock();
        long late = queue.addPlan("late", List.of(step("wait", new Guard.TimeElapsed(100))), PlanOptions.DEFAULT)
                .planId();

        facts.now = 150;
        assertTrue(QueuePolicy.ready(queue, onlyJobOf(queue, early), facts), "150 ticks after its own start");
        assertFalse(QueuePolicy.ready(queue, onlyJobOf(queue, late), facts), "the later plan has just started");
        assertEquals(
                "waits for at least 100 ticks elapsed",
                QueuePolicy.notReadyReason(queue, onlyJobOf(queue, late), facts),
                "the reason path evaluates the same frame");

        facts.now = 250;
        assertTrue(QueuePolicy.ready(queue, onlyJobOf(queue, late), facts));
    }

    @Test
    void aWhenGuardOnAPlanJobAgreesWithTheSameConditionInsideThePlan() {
        Facts facts = new Facts();
        facts.now = 1_050;
        PlanCursor start = PlanCursor.start(1_000, 0);
        PlanDefinition plan = new PlanDefinition(List.of(new PlanNode.Conditional(
                new Guard.TimeElapsed(100),
                List.of(new PlanNode.Action(step("then_action"))),
                List.of(new PlanNode.Action(step("else_action"))))));

        var transition = PlanInterpreter.advance(plan, start, facts, new PlanInterpreter.ActionResult.None());

        // Only 50 queue ticks have passed for this plan, even though the legacy elapsed fact says otherwise.
        assertEquals(
                "else_action",
                assertInstanceOf(PlanInterpreter.Event.EmitAction.class, transition.event())
                        .step()
                        .tool());
        assertEquals(Guard.Truth.TRUE, new Guard.TimeElapsed(100).evaluate(facts), "the frame-less fact is not used");
    }

    @Test
    void aBaselineAheadOfTheClockIsUnknown() {
        Facts facts = new Facts();
        facts.now = 40;
        assertEquals(
                Guard.Truth.UNKNOWN,
                new Guard.TimeElapsed(10).evaluate(facts, 50, 0),
                "a lost reading is not the same as no time having passed");
        assertEquals(Guard.Truth.FALSE, new Guard.TimeElapsed(10).evaluate(facts, 35, 0));
        assertEquals(Guard.Truth.TRUE, new Guard.TimeElapsed(10).evaluate(facts, 30, 0));
    }

    @Test
    void distanceIsMeasuredFromThePlanBaseline() {
        Facts facts = new Facts();
        Guard.DistanceTravelled guard = new Guard.DistanceTravelled(3);

        assertEquals(Guard.Truth.FALSE, guard.evaluate(facts, 0, 1_200), "2.5 blocks walked");
        assertEquals(Guard.Truth.TRUE, guard.evaluate(facts, 0, 500), "3.1 blocks walked");

        facts.odometer = 3_000;
        assertEquals(
                Guard.Truth.UNKNOWN,
                guard.evaluate(facts, 0, 4_000),
                "a baseline ahead of the meter means the reading was lost, not that no ground was covered");
    }

    @Test
    void aStandAloneJobMeasuresFromWhenItWasQueued() {
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        Facts facts = new Facts();
        for (int i = 0; i < 900; i++) queue.advanceClock();
        queue.travelledDistance(1_000);
        Job job = queue.addDirect(step("wait", new Guard.TimeElapsed(100), new Guard.DistanceTravelled(2)));

        facts.now = 950;
        facts.odometer = 1_900;
        assertFalse(QueuePolicy.ready(queue, job, facts), "50 ticks and 0.9 blocks since the job was queued");

        facts.now = 1_000;
        facts.odometer = 3_200;
        assertTrue(QueuePolicy.ready(queue, job, facts), "100 ticks and 2.2 blocks since the job was queued");
    }

    @Test
    void theQueueSaveKeepsProgressBaselines() {
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        queue.travelledDistance(4_321);
        for (int i = 0; i < 700; i++) queue.advanceClock();
        long flat =
                queue.addPlan("flat", List.of(step("a")), PlanOptions.DEFAULT).planId();
        long flatJob = queue.jobsOfPlan(flat).getFirst().id();
        Job direct = queue.addDirect(step("b"));

        QueueSave.Decoded decoded = QueueSave.decode(QueueSave.encode(queue, 0), QueueLimits.DEFAULTS)
                .orElseThrow();
        JobQueue back = decoded.queue();

        assertEquals(4_321, back.travelledDistance());
        assertNull(back.plan(flat).orElseThrow().cursor(), "a flat plan keeps no cursor");
        assertEquals(700, QueuePolicy.progressStartTick(back, back.job(flatJob).orElseThrow()));
        assertEquals(
                4_321, QueuePolicy.progressStartDistance(back, back.job(flatJob).orElseThrow()));
        assertEquals(700, back.job(direct.id()).orElseThrow().queuedTick());
        assertEquals(4_321, back.job(direct.id()).orElseThrow().queuedDistance());

        // A record written before the queue carried these fields simply has no baseline: it must still load.
        String legacy = "{\"clock\":5,\"nextJob\":2,\"nextPlan\":1,\"nextOrder\":100,"
                + "\"plans\":[],\"archives\":[],\"holds\":[],\"active\":0,"
                + "\"jobs\":[{\"id\":1,\"plan\":0,\"tool\":\"a\",\"args\":{},\"priority\":0,\"explicit\":false,"
                + "\"order\":100,\"state\":\"PENDING\",\"onUnmet\":\"WAIT\",\"onFailure\":\"ASK\",\"maxAttempts\":1,"
                + "\"attempts\":0,\"result\":\"\",\"retryAt\":0,\"guards\":[],\"after\":[]}]}";
        JobQueue legacyQueue = QueueSave.decode(
                        com.google.gson.JsonParser.parseString(legacy).getAsJsonObject(), QueueLimits.DEFAULTS)
                .orElseThrow()
                .queue();
        Job loaded = legacyQueue.job(1).orElseThrow();
        assertEquals(-1, loaded.queuedTick(), "an unknown baseline stays unknown instead of becoming the world age");
        assertEquals(-1, loaded.queuedDistance());
        assertEquals(
                Guard.Truth.UNKNOWN,
                new Guard.TimeElapsed(50)
                        .evaluate(new Facts(), QueuePolicy.progressStartTick(legacyQueue, loaded), -1));
    }
}
