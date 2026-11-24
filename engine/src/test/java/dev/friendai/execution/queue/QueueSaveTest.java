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
    void anEditedWaitingStepLoadsWithItsArgumentsAndTheRestOfThePlan() {
        PlanDefinition definition = new PlanDefinition(List.of(new PlanNode.Conditional(
                new Guard.FluidAhead(),
                List.of(new PlanNode.Repeat(
                        List.of(new PlanNode.Action(goToStep(1, 2, 3))), new Guard.TimeElapsed(100), 3)),
                List.of(new PlanNode.Action(goToStep(9, 9, 9))))));
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        long planId = queue.addStructuredPlan(definition, PlanOptions.DEFAULT);
        var transition = PlanInterpreter.advance(
                definition,
                queue.plan(planId).orElseThrow().cursor(),
                new Facts(true),
                new PlanInterpreter.ActionResult.None());
        long jobId = queue.addJob(
                        planId,
                        assertInstanceOf(PlanInterpreter.Event.EmitAction.class, transition.event())
                                .step())
                .id();
        queue.updatePlanCursor(planId, transition.cursor().withPendingJob(jobId));
        Job other = queue.addDirect(waitStep());

        assertTrue(queue.updateArgs(jobId, goToStep(4, 5, 6).args(), null, true).ok());
        assertEquals(
                goToStep(4, 5, 6).args(),
                queue.plan(planId).orElseThrow().cursor().pendingAction().args());

        JobQueue loaded = QueueSave.decode(QueueSave.encode(queue, 0), QueueLimits.DEFAULTS)
                .orElseThrow()
                .queue();
        PlanCursor cursor = loaded.plan(planId).orElseThrow().cursor();
        assertEquals(goToStep(4, 5, 6).args(), loaded.job(jobId).orElseThrow().args());
        assertEquals(goToStep(4, 5, 6).args(), cursor.pendingAction().args());
        assertEquals("step 1 → then #1 → repeat → step 1", cursor.pendingActionPath());
        assertEquals(Map.of("step 1", true), cursor.selectedBranches());
        assertTrue(loaded.job(other.id()).isPresent(), "an unrelated job survives the edit");
        assertTrue(loaded.holdNotices().isEmpty());

        // The edit reached the admitted step; the next repeat iteration still runs the plan as written.
        var resumed = PlanInterpreter.advance(
                loaded.plan(planId).orElseThrow().definition(),
                cursor,
                new Facts(true),
                new PlanInterpreter.ActionResult.Succeeded());
        assertEquals(
                goToStep(1, 2, 3).args(),
                assertInstanceOf(PlanInterpreter.Event.EmitAction.class, resumed.event())
                        .step()
                        .args());
    }

    @Test
    void aLegacySaveWhoseEditedArgumentsMissedTheCursorLoadsTheJobValues() {
        PlanDefinition definition = oneActionPlan();
        // The cursor as it was written before the edit.
        JobQueue before = new JobQueue(QueueLimits.DEFAULTS);
        long beforePlan = before.addStructuredPlan(definition, PlanOptions.DEFAULT);
        var beforeTransition = emit(before, beforePlan, definition);
        long beforeJob =
                before.addJob(beforePlan, emittedStep(beforeTransition)).id();
        before.updatePlanCursor(beforePlan, beforeTransition.cursor().withPendingJob(beforeJob));
        // The same queue after an edit: the job carries the new arguments, and so does its cursor.
        JobQueue after = new JobQueue(QueueLimits.DEFAULTS);
        long afterPlan = after.addStructuredPlan(definition, PlanOptions.DEFAULT);
        var afterTransition = emit(after, afterPlan, definition);
        long afterJob = after.addJob(afterPlan, emittedStep(afterTransition)).id();
        after.updatePlanCursor(afterPlan, afterTransition.cursor().withPendingJob(afterJob));
        assertTrue(after.updateArgs(afterJob, goToStep(7, 8, 9).args()).ok());

        // Splice the old cursor back in, which is the shape a save written before the fix has.
        JsonObject saved = QueueSave.encode(after, 0);
        JsonObject oldCursor = QueueSave.encode(before, 0)
                .getAsJsonArray("plans")
                .get(0)
                .getAsJsonObject()
                .getAsJsonObject("cursor");
        saved.getAsJsonArray("plans").get(0).getAsJsonObject().add("cursor", oldCursor);

        JobQueue loaded =
                QueueSave.decode(saved, QueueLimits.DEFAULTS).orElseThrow().queue();

        assertEquals(goToStep(7, 8, 9).args(), loaded.job(1).orElseThrow().args());
        assertEquals(
                goToStep(7, 8, 9).args(),
                loaded.plan(1).orElseThrow().cursor().pendingAction().args());
        assertTrue(loaded.holdNotices().isEmpty());
    }

    @Test
    void aSavedContinuationThatNoLongerMatchesItsToolBlocksOnlyThatPlan() {
        PlanDefinition definition = oneActionPlan();
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        long planId = queue.addStructuredPlan(definition, PlanOptions.DEFAULT);
        var transition = emit(queue, planId, definition);
        long jobId = queue.addJob(planId, emittedStep(transition)).id();
        queue.updatePlanCursor(planId, transition.cursor().withPendingJob(jobId));
        Job other = queue.addDirect(goToStep(1, 2, 3));

        JsonObject saved = QueueSave.encode(queue, 0);
        saved.getAsJsonArray("plans")
                .get(0)
                .getAsJsonObject()
                .getAsJsonObject("cursor")
                .getAsJsonObject("pendingAction")
                .addProperty("tool", "go_to");

        JobQueue loaded =
                QueueSave.decode(saved, QueueLimits.DEFAULTS).orElseThrow().queue();

        assertTrue(loaded.plan(planId).isEmpty(), "the poisoned plan is withdrawn");
        assertTrue(loaded.archives().stream().anyMatch(a -> a.id() == planId));
        assertTrue(loaded.job(other.id()).isPresent(), "unrelated work is kept");
        assertEquals(1, loaded.holdNotices().size());
        assertTrue(loaded.holdNotices().getFirst().contains("plan #" + planId));
    }

    @Test
    void anEditThatNeverReachedItsCursorStillRoundTripsThroughThePlan() {
        PlanDefinition definition = oneActionPlan();
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        long planId = queue.addStructuredPlan(definition, PlanOptions.DEFAULT);
        var transition = emit(queue, planId, definition);
        long jobId = queue.addJob(planId, emittedStep(transition)).id();
        queue.updatePlanCursor(planId, transition.cursor().withPendingJob(jobId));

        JsonObject first = new JsonObject();
        first.addProperty("x", 1);
        JsonObject second = new JsonObject();
        second.addProperty("x", 2);
        assertTrue(queue.updateArgs(jobId, first).ok());
        assertTrue(queue.updateArgs(jobId, second).ok());

        JobQueue loaded = QueueSave.decode(QueueSave.encode(queue, 0), QueueLimits.DEFAULTS)
                .orElseThrow()
                .queue();
        assertEquals(second, loaded.job(jobId).orElseThrow().args());
        assertEquals(
                second,
                loaded.plan(planId).orElseThrow().cursor().pendingAction().args());
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

    /** Advances a fresh structured plan to its first emitted action. */
    private static PlanInterpreter.Transition emit(JobQueue queue, long planId, PlanDefinition definition) {
        return PlanInterpreter.advance(
                definition,
                queue.plan(planId).orElseThrow().cursor(),
                new Facts(false),
                new PlanInterpreter.ActionResult.None());
    }

    private static QueuedStep emittedStep(PlanInterpreter.Transition transition) {
        return assertInstanceOf(PlanInterpreter.Event.EmitAction.class, transition.event())
                .step();
    }

    private static QueuedStep goToStep(int x, int y, int z) {
        JsonObject args = new JsonObject();
        args.addProperty("x", x);
        args.addProperty("y", y);
        args.addProperty("z", z);
        return new QueuedStep("go_to", args, null, List.of(), GuardAction.WAIT, null, 1, List.of());
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
