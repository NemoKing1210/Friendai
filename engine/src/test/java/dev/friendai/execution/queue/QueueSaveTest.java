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
        public boolean fluidAhead() {
            return fluidAhead;
        }
    }
}
