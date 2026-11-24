package dev.friendai.execution.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Regression tests for defects found while reviewing the task queue. */
class QueueReviewFixesTest {
    private static QueuedStep step(String tool, int... after) {
        return new QueuedStep(
                tool,
                new JsonObject(),
                null,
                List.of(),
                GuardAction.WAIT,
                null,
                1,
                java.util.Arrays.stream(after).boxed().toList());
    }

    private static JobQueue plan(String... tools) {
        JobQueue q = new JobQueue(QueueLimits.DEFAULTS);
        q.addPlan("p", java.util.Arrays.stream(tools).map(t -> step(t)).toList(), PlanOptions.DEFAULT);
        return q;
    }

    @Test
    void dependentOfSkippedFailureDoesNotStickForever() {
        JobQueue q = new JobQueue(QueueLimits.DEFAULTS);
        q.addPlan("p", List.of(step("a"), step("b", 1)), new PlanOptions(0, FailurePolicy.SKIP, 0));
        q.markRunning(1);
        q.cancel(1);
        assertEquals(PlanStatus.NEEDS_DECISION, QueuePolicy.planStatus(q, 1));
    }

    @Test
    void anOrdinarySkipLetsAnOrderOnlyDependentRunButARequiredEffectStillWaits() {
        JobQueue q = new JobQueue(QueueLimits.DEFAULTS);
        QueuedStep first = new QueuedStep(
                "a", new JsonObject(), null, List.of(), GuardAction.WAIT, FailurePolicy.ASK, 1, List.of());
        QueuedStep ordered = new QueuedStep(
                "b", new JsonObject(), null, List.of(), GuardAction.WAIT, FailurePolicy.ASK, 1, List.of(1));
        QueuedStep needsOutput = new QueuedStep(
                "c",
                new JsonObject(),
                null,
                List.of(new Guard.HasItem("minecraft:oak_planks", 4)),
                GuardAction.WAIT,
                FailurePolicy.ASK,
                1,
                List.of(1));
        JobQueue.Added added = q.addPlan("p", List.of(first, ordered, needsOutput), PlanOptions.DEFAULT);
        GuardFacts empty = new FakeGuardFacts(Map.of(), 10, 1, 0, Map.of(), "minecraft:overworld");
        assertTrue(q.skip(added.jobIds().get(0)).ok());

        // #2 asked only for ordering, so it proceeds; #3 needs the item #1 never produced and keeps its guard.
        assertTrue(QueuePolicy.ready(q, q.job(added.jobIds().get(1)).orElseThrow(), empty));
        assertFalse(QueuePolicy.ready(q, q.job(added.jobIds().get(2)).orElseThrow(), empty));
        assertEquals(
                "waits for 4 minecraft:oak_planks",
                QueuePolicy.notReadyReason(q, q.job(added.jobIds().get(2)).orElseThrow(), empty));
        assertEquals(PlanStatus.ACTIVE, QueuePolicy.planStatus(q, added.planId()));
        assertEquals(
                Job.Omission.OWNER, Job.omission(q.job(added.jobIds().get(0)).orElseThrow()));
    }

    @Test
    void aConditionSkippedStepAndAContinuedFailureRecordDifferentOmissions() {
        JobQueue q = new JobQueue(QueueLimits.DEFAULTS);
        JobQueue.Added added = q.addPlan("p", List.of(step("a"), step("b")), new PlanOptions(0, FailurePolicy.SKIP, 0));

        assertTrue(q.skip(added.jobIds().get(0), Job.SKIP_CONDITION).ok());
        q.markRunning(added.jobIds().get(1));
        q.fail(added.jobIds().get(1), "no room to place");

        assertEquals(
                Job.Omission.NOT_APPLICABLE,
                Job.omission(q.job(added.jobIds().get(0)).orElseThrow()));
        assertEquals(
                Job.Omission.CONTINUED,
                Job.omission(q.job(added.jobIds().get(1)).orElseThrow()));
        assertEquals(
                "no room to place", q.job(added.jobIds().get(1)).orElseThrow().result());
        assertEquals(PlanStatus.DONE, QueuePolicy.planStatus(q, added.planId()));
    }

    @Test
    void anOwnerSkipOfAFailedStepKeepsTheFailureItSettledWith() {
        JobQueue q = plan("a");
        q.markRunning(1);
        q.failPermanently(1, "the site is protected");

        assertTrue(q.skip(1).ok());

        // The owner asked to move on, but the reason the step failed is still what a reviewer needs.
        assertEquals("the site is protected", q.job(1).orElseThrow().result());
        assertEquals(Job.Omission.CONTINUED, Job.omission(q.job(1).orElseThrow()));
    }

    @Test
    void moveRenumbersWithoutCollisions() {
        JobQueue q = plan("a", "b", "c");
        assertTrue(q.move(3, 1).ok());
        List<Long> orders = q.jobs().stream().map(Job::order).distinct().toList();
        assertEquals(3, orders.size());
        assertTrue(q.job(3).orElseThrow().order() < q.job(1).orElseThrow().order());
    }

    @Test
    void cancelOfTerminalJobIsRejected() {
        JobQueue q = plan("a");
        q.markRunning(1);
        q.finish(1, "ok");
        assertFalse(q.cancel(1).ok());
    }

    @Test
    void storedResultIsBounded() {
        JobQueue q = plan("a");
        q.markRunning(1);
        q.finish(1, "r".repeat(5000));
        assertTrue(q.job(1).orElseThrow().result().length() <= 300);
    }

    @Test
    void mergeRepointsDependentsOfTheRemovedJob() {
        JobQueue q = plan("a", "a", "c");
        q.replace(3, List.of(step("c", 2)));
        long dependent = q.jobs().stream()
                .filter(j -> j.tool().equals("c"))
                .findFirst()
                .orElseThrow()
                .id();
        assertTrue(q.merge(1, 2, null).ok());
        assertTrue(QueuePolicy.dependenciesDone(q, q.job(dependent).orElseThrow())
                || !q.job(dependent).orElseThrow().after().contains(2L));
    }

    @Test
    void requeueTurnsRunningJobBackToPending() {
        JobQueue q = plan("a");
        q.markRunning(1);
        q.requeue(1);
        assertEquals(JobState.PENDING, q.job(1).orElseThrow().state());
    }
}
