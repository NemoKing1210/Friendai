package dev.friendai.execution.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QueueSaveTest {
    @Test
    void roundTripsQueueAndActiveJobIdentity() {
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        long id = queue.addPlan(
                        "build",
                        List.of(new QueuedStep(
                                "place_block",
                                new JsonObject(),
                                null,
                                List.of(new Guard.HasItem("minecraft:stone", 2)),
                                GuardAction.WAIT,
                                null,
                                1,
                                List.of())),
                        PlanOptions.DEFAULT)
                .jobIds()
                .getFirst();
        queue.markRunning(id);

        QueueSave.Decoded decoded = QueueSave.decode(QueueSave.encode(queue, id), QueueLimits.DEFAULTS)
                .orElseThrow();
        assertEquals("build", decoded.queue().plan(1).orElseThrow().goal());
        assertEquals(id, decoded.activeJobId());
    }

    @Test
    void aRelativeDeferralSurvivesReloadWithItsAbsoluteTick() {
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        long planId = queue.addPlan(
                        "p",
                        List.of(new QueuedStep(
                                "wait", new JsonObject(), null, List.of(), GuardAction.WAIT, null, 1, List.of())),
                        PlanOptions.DEFAULT)
                .planId();
        queue.setHold(HoldKey.plan(planId), QueueHold.defer("later", queue.clock() + 20, null));
        long wakeAt = queue.hold(HoldKey.plan(planId)).orElseThrow().wakeTick();
        QueueHold restored = QueueSave.decode(QueueSave.encode(queue, 0), QueueLimits.DEFAULTS)
                .orElseThrow()
                .queue()
                .hold(HoldKey.plan(planId))
                .orElseThrow();
        assertEquals(wakeAt, restored.wakeTick());
        assertEquals("later", restored.reason());
    }

    @Test
    void rejectsMalformedQueueShapes() {
        assertTrue(QueueSave.decode(null, QueueLimits.DEFAULTS).isEmpty());
        assertTrue(QueueSave.decode(new JsonObject(), QueueLimits.DEFAULTS).isEmpty());
        JsonObject malformed = new JsonObject();
        malformed.addProperty("plans", "not an array");
        malformed.add("jobs", new JsonArray());
        malformed.add("archives", new JsonArray());
        assertTrue(QueueSave.decode(malformed, QueueLimits.DEFAULTS).isEmpty());
    }

    @Test
    void roundTripsInitiatingPrincipalWithoutPersistingAuthority() {
        UUID principal = UUID.fromString("46fab118-0a82-4f7e-9e7b-a9b48bf34827");
        QueuedStep step = new QueuedStep(
                "place_block",
                new JsonObject(),
                null,
                List.of(),
                GuardAction.WAIT,
                FailurePolicy.STOP_PLAN,
                1,
                List.of(),
                principal,
                false);
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        queue.addDirect(step);

        JsonObject saved = QueueSave.encode(queue, 0);
        Job restored = QueueSave.decode(saved, QueueLimits.DEFAULTS)
                .orElseThrow()
                .queue()
                .job(1)
                .orElseThrow();

        assertEquals(principal, restored.principal());
        assertTrue(!restored.creativeSensitive());
        assertTrue(saved.toString().contains(principal.toString()));
        assertTrue(!saved.toString().contains("access"));
    }

    @Test
    void roundTripsSelectedBranchAndMidLoopCursorWithoutReplayingPendingAction() {
        PlanDefinition definition = new PlanDefinition(List.of(new PlanNode.Conditional(
                new Guard.FluidAhead(),
                List.of(new PlanNode.Repeat(
                        List.of(new PlanNode.Action(new QueuedStep(
                                "go_to", new JsonObject(), null, List.of(), GuardAction.WAIT, null, 1, List.of()))),
                        new Guard.TimeElapsed(100),
                        3)),
                List.of())));
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        long planId = queue.addStructuredPlan(definition, PlanOptions.DEFAULT);
        var transition = PlanInterpreter.advance(
                definition,
                queue.plan(planId).orElseThrow().cursor(),
                new Facts(true),
                new PlanInterpreter.ActionResult.None());
        var emitted = assertInstanceOf(PlanInterpreter.Event.EmitAction.class, transition.event());
        long jobId = queue.addJob(planId, emitted.step()).id();
        queue.updatePlanCursor(planId, transition.cursor().withPendingJob(jobId));

        PlanRecord restored = QueueSave.decode(QueueSave.encode(queue, 0), QueueLimits.DEFAULTS)
                .orElseThrow()
                .queue()
                .plan(planId)
                .orElseThrow();
        assertEquals(Map.of("step 1", true), restored.cursor().selectedBranches());
        assertEquals(Map.of("step 1 → then #1", 1), restored.cursor().repeatIterations());
        assertEquals("step 1 → then #1 → repeat → step 1", restored.cursor().pendingActionPath());
        assertEquals("go_to", restored.cursor().pendingAction().tool());
        assertEquals(jobId, restored.cursor().pendingJobId());
        var resumed = PlanInterpreter.advance(
                restored.definition(), restored.cursor(), new Facts(false), new PlanInterpreter.ActionResult.None());
        assertEquals(
                "step 1 → then #1 → repeat → step 1",
                assertInstanceOf(PlanInterpreter.Event.EmitAction.class, resumed.event())
                        .path());
    }

    @Test
    void holdsRoundTripUnderTheirOwnScope() {
        JobQueue queue = queueWithConditionalPlanAndDirectJob();
        queue.setHold(HoldKey.plan(1), QueueHold.pause("plan paused"));
        queue.setHold(HoldKey.job(1), QueueHold.defer("job deferred", queue.clock() + 30, null));

        JobQueue loaded = QueueSave.decode(QueueSave.encode(queue, 0), QueueLimits.DEFAULTS)
                .orElseThrow()
                .queue();

        assertEquals("plan paused", loaded.hold(HoldKey.plan(1)).orElseThrow().reason());
        assertEquals("job deferred", loaded.hold(HoldKey.job(1)).orElseThrow().reason());
        assertEquals(2, loaded.holds().size());
        assertTrue(loaded.holdNotices().isEmpty(), "a scoped save needs no notice");
    }

    @Test
    void aLegacyHoldOnANumberOnlyOneTargetOwnsKeepsItsScope() {
        JobQueue jobOnly = new JobQueue(QueueLimits.DEFAULTS);
        jobOnly.addDirect(waitStep());
        jobOnly.setHold(HoldKey.job(1), QueueHold.pause("job paused"));
        JobQueue loadedJob = QueueSave.decode(legacyHolds(QueueSave.encode(jobOnly, 0)), QueueLimits.DEFAULTS)
                .orElseThrow()
                .queue();
        assertEquals("job paused", loadedJob.hold(HoldKey.job(1)).orElseThrow().reason());
        assertTrue(loadedJob.hold(HoldKey.plan(1)).isEmpty());
        assertTrue(loadedJob.holdNotices().isEmpty());

        JobQueue planOnly = new JobQueue(QueueLimits.DEFAULTS);
        planOnly.addStructuredPlan(oneActionPlan(), PlanOptions.DEFAULT);
        planOnly.setHold(HoldKey.plan(1), QueueHold.pause("plan paused"));
        JobQueue loadedPlan = QueueSave.decode(legacyHolds(QueueSave.encode(planOnly, 0)), QueueLimits.DEFAULTS)
                .orElseThrow()
                .queue();
        assertEquals(
                "plan paused", loadedPlan.hold(HoldKey.plan(1)).orElseThrow().reason());
        assertTrue(loadedPlan.hold(HoldKey.job(1)).isEmpty());
        assertTrue(loadedPlan.holdNotices().isEmpty());
    }

    @Test
    void aLegacyHoldOnAnAmbiguousNumberHoldsBothAndTellsTheOwner() {
        JobQueue queue = queueWithConditionalPlanAndDirectJob();
        queue.setHold(HoldKey.job(1), QueueHold.pause("kept"));

        JobQueue loaded = QueueSave.decode(legacyHolds(QueueSave.encode(queue, 0)), QueueLimits.DEFAULTS)
                .orElseThrow()
                .queue();

        assertTrue(loaded.hold(HoldKey.plan(1)).isPresent(), "plan #1 keeps the hold");
        assertTrue(loaded.hold(HoldKey.job(1)).isPresent(), "job #1 keeps the hold too");
        assertEquals(1, loaded.holdNotices().size());
        String notice = loaded.holdNotices().getFirst();
        assertTrue(notice.contains("plan #1") && notice.contains("job #1"), notice);
        assertTrue(QueueText.show(loaded, new Facts(false), null, 10, false).contains("[paused (kept)]"));
    }

    @Test
    void aLegacyHoldOnAMissingTargetIsDropped() {
        JsonObject saved = new JsonObject();
        saved.addProperty("clock", 0);
        saved.addProperty("nextJob", 1);
        saved.addProperty("nextPlan", 1);
        saved.addProperty("nextOrder", 100);
        saved.add("plans", new JsonArray());
        saved.add("jobs", new JsonArray());
        saved.add("archives", new JsonArray());
        saved.addProperty("active", 0);
        JsonArray holds = new JsonArray();
        JsonObject hold = new JsonObject();
        hold.addProperty("id", 9);
        hold.addProperty("kind", "PAUSE");
        hold.addProperty("reason", "stale");
        hold.addProperty("remaining", 0);
        holds.add(hold);
        saved.add("holds", holds);

        JobQueue loaded =
                QueueSave.decode(saved, QueueLimits.DEFAULTS).orElseThrow().queue();

        assertTrue(loaded.holds().isEmpty(), "a hold with no live target holds nothing");
        assertTrue(loaded.holdNotices().isEmpty());
    }

    /** Copies one queue document with its holds reset to the pre-scope shape. */
    private static JsonObject legacyHolds(JsonObject saved) {
        for (var e : saved.getAsJsonArray("holds")) e.getAsJsonObject().remove("scope");
        return saved;
    }

    /** A structured plan #1 with no jobs yet and a direct job #1: both counters name one. */
    private static JobQueue queueWithConditionalPlanAndDirectJob() {
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        queue.addStructuredPlan(oneActionPlan(), PlanOptions.DEFAULT);
        queue.addDirect(waitStep());
        return queue;
    }

    private static PlanDefinition oneActionPlan() {
        return new PlanDefinition(List.of(new PlanNode.Action(waitStep())));
    }

    private static QueuedStep waitStep() {
        return new QueuedStep("wait", new JsonObject(), null, List.of(), GuardAction.WAIT, null, 1, List.of());
    }

    private record Facts(boolean fluidAhead) implements GuardFacts {
        @Override
        public int countItems(String id) {
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
        public int countBlocks(String id, int radius) {
            return 0;
        }

        @Override
        public String dimension() {
            return "minecraft:overworld";
        }

        @Override
        public long simulationTick() {
            return 0;
        }

        @Override
        public boolean fluidAhead() {
            return fluidAhead;
        }
    }
}
