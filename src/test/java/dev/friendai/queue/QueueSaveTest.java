package dev.friendai.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QueueSaveTest {
    @Test
    void roundTripsQueueAndActiveSave() {
        JobQueue q = new JobQueue(QueueLimits.DEFAULTS);
        long id = q.addPlan("build", List.of(new QueuedStep("place_block", new JsonObject(), null,
                List.of(new Guard.HasItem("minecraft:stone", 2)), GuardAction.WAIT, null, 1, List.of())), PlanOptions.DEFAULT).jobIds().getFirst();
        q.markRunning(id);
        String saved = QueueSave.encode(q, "build:progress");
        QueueSave.Decoded decoded = QueueSave.decode(saved, QueueLimits.DEFAULTS).orElseThrow();
        assertEquals("queue:v2:", saved.substring(0, 9));
        assertEquals("build", decoded.queue().plan(1).orElseThrow().goal());
        assertEquals(id, decoded.activeJobId());
        assertEquals("build:progress", decoded.activeTaskSave());
    }

    @Test
    void rejectsBrokenOrUnknownVersions() {
        assertTrue(QueueSave.decode("queue:v1:{bad", QueueLimits.DEFAULTS).isEmpty());
        assertTrue(QueueSave.decode("queue:v9:{}", QueueLimits.DEFAULTS).isEmpty());
    }

    @Test
    void roundTripsInitiatingPrincipalWithoutPersistingAuthority() {
        UUID principal = UUID.fromString("46fab118-0a82-4f7e-9e7b-a9b48bf34827");
        QueuedStep step = new QueuedStep("place_block", new JsonObject(), null, List.of(),
                GuardAction.WAIT, FailurePolicy.STOP_PLAN, 1, List.of(), principal, false);
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        queue.addDirect(step);

        String saved = QueueSave.encode(queue, null);
        Job restored = QueueSave.decode(saved, QueueLimits.DEFAULTS).orElseThrow()
                .queue().job(1).orElseThrow();

        assertEquals(principal, restored.principal());
        assertTrue(!restored.creativeSensitive());
        assertTrue(saved.contains(principal.toString()));
        assertTrue(!saved.contains("access"));
    }

    @Test
    void oldQueueSaveHasNoInitiatingPrincipal() {
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        queue.addDirect(new QueuedStep("place_block", new JsonObject(), null, List.of(),
                GuardAction.WAIT, FailurePolicy.STOP_PLAN, 1, List.of()));

        Job restored = QueueSave.decode(QueueSave.encode(queue, null), QueueLimits.DEFAULTS)
                .orElseThrow().queue().job(1).orElseThrow();

        assertEquals(null, restored.principal());    }

    @Test
    void migratesLegacyFlatQueueAndPreservesRunningJobIdentity() {
        String legacy = "queue:v1:{\"clock\":4,\"nextJob\":8,\"nextPlan\":3,\"nextOrder\":200,"
                + "\"plans\":[{\"id\":2,\"goal\":\"old\",\"priority\":1,\"added\":1,\"deadline\":0,\"onFailure\":\"ASK\"}],"
                + "\"jobs\":[{\"id\":7,\"plan\":2,\"tool\":\"go_to\",\"args\":{},\"priority\":1,\"explicit\":false,\"order\":100,"
                + "\"state\":\"RUNNING\",\"onUnmet\":\"WAIT\",\"onFailure\":\"ASK\",\"maxAttempts\":1,\"attempts\":0,"
                + "\"result\":\"\",\"retryAt\":0,\"guards\":[],\"after\":[]}],\"archives\":[],\"active\":7}";

        QueueSave.Decoded decoded = QueueSave.decode(legacy, QueueLimits.DEFAULTS).orElseThrow();
        assertEquals(7, decoded.activeJobId());
        assertEquals("go_to", decoded.queue().plan(2).orElseThrow().definition().nodes().getFirst()
                .getClass() == PlanNode.Action.class
                ? ((PlanNode.Action) decoded.queue().plan(2).orElseThrow().definition().nodes().getFirst()).step().tool() : "");
        assertEquals(JobState.RUNNING, decoded.queue().job(7).orElseThrow().state());
    }

    @Test
    void v2RoundTripsSelectedBranchAndMidLoopCursorWithoutReplayingPendingAction() {
        PlanDefinition definition = new PlanDefinition(List.of(new PlanNode.Conditional(new Guard.FluidAhead(),
                List.of(new PlanNode.Repeat(List.of(new PlanNode.Action(new QueuedStep("go_to", new JsonObject(), null,
                        List.of(), GuardAction.WAIT, null, 1, List.of()))), new Guard.TimeElapsed(100), 3)), List.of())));
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        long planId = queue.addStructuredPlan(definition, PlanOptions.DEFAULT);
        var transition = PlanInterpreter.advance(definition, queue.plan(planId).orElseThrow().cursor(),
                new GuardFacts() {
                    public int countItems(String id) { return 0; }
                    public int emptySlots() { return 0; }
                    public float healthFraction() { return 1; }
                    public int ownerDistance() { return 0; }
                    public int countBlocks(String id, int radius) { return 0; }
                    public String dimension() { return "minecraft:overworld"; }
                    public boolean fluidAhead() { return true; }
                }, new PlanInterpreter.ActionResult.None());
        var emitted = assertInstanceOf(PlanInterpreter.Event.EmitAction.class, transition.event());
        long jobId = queue.addJob(planId, emitted.step()).id();
        queue.updatePlanCursor(planId, transition.cursor().withPendingJob(jobId));
        String saved = QueueSave.encode(queue, null);

        PlanRecord restored = QueueSave.decode(saved, QueueLimits.DEFAULTS).orElseThrow().queue().plan(planId).orElseThrow();
        assertEquals(Map.of("step 1", true), restored.cursor().selectedBranches());
        assertEquals(Map.of("step 1 → then #1", 1), restored.cursor().repeatIterations());
        assertEquals("step 1 → then #1 → repeat → step 1", restored.cursor().pendingActionPath());
        assertEquals("go_to", restored.cursor().pendingAction().tool());
        assertEquals(jobId, restored.cursor().pendingJobId());
        var resumed = PlanInterpreter.advance(restored.definition(), restored.cursor(), new GuardFacts() {
            public int countItems(String id) { return 0; }
            public int emptySlots() { return 0; }
            public float healthFraction() { return 1; }
            public int ownerDistance() { return 0; }
            public int countBlocks(String id, int radius) { return 0; }
            public String dimension() { return "minecraft:overworld"; }
            public boolean fluidAhead() { return false; }
        }, new PlanInterpreter.ActionResult.None());
        assertEquals("step 1 → then #1 → repeat → step 1",
                assertInstanceOf(PlanInterpreter.Event.EmitAction.class, resumed.event()).path());
    }
}
