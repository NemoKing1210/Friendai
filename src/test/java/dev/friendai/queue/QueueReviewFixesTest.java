package dev.friendai.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.List;
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
