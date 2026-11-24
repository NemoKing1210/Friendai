package dev.friendai.execution.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Evidence for a prerequisite has to outlive the plan or goal that produced it. */
class TerminalPrerequisiteTest {
    private static QueuedStep step(String tool) {
        return new QueuedStep(tool, new JsonObject(), null, List.of(), GuardAction.WAIT, null, 1, List.of());
    }

    private static QueuedStep guarded(String tool, Guard guard) {
        return new QueuedStep(tool, new JsonObject(), null, List.of(guard), GuardAction.WAIT, null, 1, List.of());
    }

    private static void finishPlan(JobQueue queue, long planId) {
        for (Job job : List.copyOf(queue.jobs())) {
            if (job.planId() != planId) continue;
            queue.markRunning(job.id());
            queue.finish(job.id(), "done");
        }
    }

    private static List<Long> archivedIds(JobQueue queue) {
        return queue.archives().stream().map(PlanArchive::id).toList();
    }

    @Test
    void anArchiveAQueuedJobStillAsksAboutSurvivesTrimming() {
        JobQueue queue = new JobQueue(new QueueLimits(64, 8, 32, 8192, 1));
        long first =
                queue.addPlan("first", List.of(step("a")), PlanOptions.DEFAULT).planId();
        finishPlan(queue, first);
        queue.archivePlan(first, true, "finished");

        long dependent =
                queue.addDirect(guarded("b", new Guard.PlanDone(first))).id();
        long later =
                queue.addPlan("later", List.of(step("c")), PlanOptions.DEFAULT).planId();
        finishPlan(queue, later);
        queue.archivePlan(later, true, "finished");

        // The limit is one, but the dependent job still needs the first plan's outcome, so the newest
        // unreferenced archive is the one dropped.
        assertEquals(List.of(first), archivedIds(queue));

        queue.markRunning(dependent);
        queue.finish(dependent, "done");
        assertTrue(queue.remove(dependent).ok(), "the driver drops a finished job");
        long newest =
                queue.addPlan("newest", List.of(step("d")), PlanOptions.DEFAULT).planId();
        finishPlan(queue, newest);
        queue.archivePlan(newest, true, "finished");

        assertEquals(List.of(newest), archivedIds(queue), "with the reference gone the old archives are expendable");
    }

    @Test
    void anUnreferencedArchiveIsDroppedAtTheLimit() {
        JobQueue queue = new JobQueue(new QueueLimits(64, 8, 32, 8192, 1));
        for (int i = 0; i < 3; i++) {
            long plan = queue.addPlan("p" + i, List.of(step("t" + i)), PlanOptions.DEFAULT)
                    .planId();
            finishPlan(queue, plan);
            queue.archivePlan(plan, true, "finished");
        }

        assertEquals(List.of(3L), archivedIds(queue));
    }

    @Test
    void planReferencesComposeThroughGroupsAndThePlanTree() {
        Guard guard = new Guard.AllOf(List.of(
                new Guard.PlanDone(3),
                new Guard.Not(new Guard.AnyOf(List.of(new Guard.PlanDone(4), new Guard.FluidAhead())))));
        assertEquals(Set.of(3L, 4L), guard.referencedPlanIds());

        PlanDefinition plan = new PlanDefinition(List.of(
                new PlanNode.Conditional(new Guard.PlanDone(5), List.of(new PlanNode.Action(step("a"))), List.of()),
                new PlanNode.Repeat(List.of(new PlanNode.Action(step("b"))), new Guard.PlanDone(6), 2)));
        assertEquals(Set.of(5L, 6L), plan.referencedPlanIds());
        assertTrue(new PlanDefinition(List.of(new PlanNode.Action(step("c"))))
                .referencedPlanIds()
                .isEmpty());
    }

    @Test
    void aPlanMayNotWaitForItsOwnCompletion() {
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class,
                () -> queue.addPlan("self", List.of(guarded("wait", new Guard.PlanDone(1))), PlanOptions.DEFAULT));
        assertEquals("a plan cannot wait for its own completion", thrown.getMessage());
        assertTrue(queue.plans().isEmpty());
        assertEquals(1, queue.nextPlanId(), "a refused plan does not burn its id");

        PlanDefinition selfWaiting = new PlanDefinition(List.of(
                new PlanNode.Conditional(new Guard.PlanDone(1), List.of(new PlanNode.Action(step("a"))), List.of())));
        assertThrows(IllegalArgumentException.class, () -> queue.addStructuredPlan(selfWaiting, PlanOptions.DEFAULT));
        assertTrue(queue.plans().isEmpty());

        long accepted = queue.addStructuredPlan(
                new PlanDefinition(List.of(new PlanNode.Action(step("b")))), PlanOptions.DEFAULT);
        PlanDefinition patched = new PlanDefinition(List.of(new PlanNode.Conditional(
                new Guard.PlanDone(accepted), List.of(new PlanNode.Action(step("c"))), List.of())));
        JobQueue.Result refused = queue.updatePlanDefinition(accepted, patched, PlanCursor.start(0, 0));
        assertFalse(refused.ok());
        assertEquals("a plan cannot wait for its own completion", refused.text());
        assertEquals(1, queue.plans().getFirst().definition().nodes().size(), "the plan keeps its old tree");
    }
}
