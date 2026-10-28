package dev.friendai.execution.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JobQueueTest {
    private static QueuedStep step(String tool) {
        return new QueuedStep(tool, new JsonObject(), null, List.of(), GuardAction.WAIT, null, 1, List.of());
    }

    @Test
    void addPlanAssignsIdsOrdersAndPlanPriority() {
        JobQueue q = new JobQueue(QueueLimits.DEFAULTS);
        JobQueue.Added added = q.addPlan(
                "build", List.of(step("mine_blocks"), step("place_block")), new PlanOptions(5, FailurePolicy.ASK, 0));
        assertEquals(List.of(1L, 2L), added.jobIds());
        assertEquals(5, q.job(1).orElseThrow().priority());
        assertEquals(100, q.job(1).orElseThrow().order());
        assertEquals(1, q.planCount());
    }

    @Test
    void directJobHasNoPlanRecordAndNoPlanSlot() {
        JobQueue q = new JobQueue(new QueueLimits(2, 1, 32, 8192, 5));
        q.addPlan("p", List.of(step("a")), PlanOptions.DEFAULT);
        Job direct = q.addDirect(step("b"));
        assertEquals(0, direct.planId());
        assertTrue(q.plan(0).isEmpty());
        assertEquals(1, q.planCount());
        assertEquals(2, q.jobCount());
    }

    @Test
    void afterStepsBecomeEarlierJobIdsAndForwardReferencesFail() {
        QueuedStep second =
                new QueuedStep("place_block", new JsonObject(), null, List.of(), GuardAction.WAIT, null, 1, List.of(1));
        JobQueue.Added added = new JobQueue(QueueLimits.DEFAULTS)
                .addPlan("p", List.of(step("mine_blocks"), second), PlanOptions.DEFAULT);
        JobQueue q = new JobQueue(QueueLimits.DEFAULTS);
        assertThrows(
                IllegalArgumentException.class,
                () -> q.addPlan(
                        "p",
                        List.of(new QueuedStep(
                                "go_to", new JsonObject(), null, List.of(), GuardAction.WAIT, null, 1, List.of(1))),
                        PlanOptions.DEFAULT));
        assertEquals(List.of(1L, 2L), added.jobIds());
    }

    @Test
    void limitsRejectWithoutChangingQueue() {
        JobQueue q = new JobQueue(new QueueLimits(2, 1, 2, 8192, 5));
        q.addPlan("p", List.of(step("a"), step("b")), PlanOptions.DEFAULT);
        assertThrows(IllegalArgumentException.class, () -> q.addPlan("q", List.of(step("c")), PlanOptions.DEFAULT));
        assertEquals(2, q.jobCount());
    }

    @Test
    void updatedRuntimeLimitsApplyToNewWorkWithoutDiscardingQueuedJobs() {
        JobQueue q = new JobQueue(new QueueLimits(2, 2, 2, 8192, 5));
        q.addDirect(step("a"));
        q.addDirect(step("b"));

        q.setLimits(new QueueLimits(1, 1, 1, 8192, 3));

        assertEquals(2, q.jobCount());
        assertEquals(1, q.limits().maxJobs());
        assertThrows(IllegalArgumentException.class, () -> q.addDirect(step("c")));
    }

    @Test
    void movePriorityUpdateRetrySkipAndArchiveAreExplicit() {
        JobQueue q = new JobQueue(QueueLimits.DEFAULTS);
        JobQueue.Added added = q.addPlan("p", List.of(step("a"), step("b")), PlanOptions.DEFAULT);
        long first = added.jobIds().getFirst();
        long second = added.jobIds().get(1);
        assertTrue(q.move(second, first).ok());
        assertTrue(q.setPriority(first, 7).ok());
        assertEquals(7, q.job(first).orElseThrow().priority());
        assertTrue(q.updateArgs(first, new JsonObject()).ok());
        q.markRunning(first);
        q.fail(first, "bad");
        assertTrue(q.retry(first).ok());
        assertTrue(q.skip(first).ok());
        q.markRunning(second);
        q.finish(second, "done");
        q.archivePlan(added.planId());
        assertEquals(0, q.jobCount());
        assertEquals(1, q.archives().size());
    }

    @Test
    void editingPendingJobRebindsItsInitiatorAndCreativeSensitivityAtomically() {
        UUID original = UUID.randomUUID();
        UUID editor = UUID.randomUUID();
        QueuedStep originalStep = new QueuedStep(
                "place_block", new JsonObject(), null, List.of(), GuardAction.WAIT, null, 1, List.of(), original, true);
        JobQueue q = new JobQueue(QueueLimits.DEFAULTS);
        long id = q.addDirect(originalStep).id();

        assertTrue(q.updateArgs(id, new JsonObject(), editor, false).ok());

        assertEquals(editor, q.job(id).orElseThrow().principal());
        assertTrue(!q.job(id).orElseThrow().creativeSensitive());
    }

    @Test
    void structuredPlanStoresTreeWithoutJobsAndCancellationClosesItsCursor() {
        PlanDefinition definition = new PlanDefinition(List.of(new PlanNode.Conditional(
                new Guard.FluidAhead(), List.of(new PlanNode.Action(step("work"))), List.of())));
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        long planId = queue.addStructuredPlan(definition, PlanOptions.DEFAULT);

        assertEquals(1, queue.planCount());
        assertEquals(0, queue.jobCount());
        assertEquals(definition, queue.plan(planId).orElseThrow().definition());
        Job emitted = queue.addJob(planId, step("work"));
        assertEquals(planId, emitted.planId());
        assertEquals(1, queue.jobCount());

        queue.cancelPlan(planId);
        assertThrows(IllegalArgumentException.class, () -> queue.addJob(planId, step("later")));
    }
}
