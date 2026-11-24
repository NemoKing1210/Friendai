package dev.friendai.execution.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JobQueueInsertPrerequisiteTest {
    private static final GuardFacts FACTS = new FakeGuardFacts(Map.of(), 4, 1f, 0, Map.of(), "minecraft:overworld");

    private static QueuedStep step(String tool) {
        return new QueuedStep(tool, new JsonObject(), null, List.of(), GuardAction.WAIT, null, 1, List.of());
    }

    @Test
    void aPrerequisiteRunsBeforeTheBlockedJobAndTheJobKeepsItsIdentity() {
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        JobQueue.Added added = queue.addPlan("p", List.of(step("place_block")), PlanOptions.DEFAULT);
        long blocked = added.jobIds().getFirst();

        JobQueue.Result result = queue.insertPrerequisite(blocked, step("obtain_item"));
        assertTrue(result.ok(), result.text());
        Job remedy = queue.jobs().stream()
                .filter(job -> job.planId() == 0 && job.tool().equals("obtain_item"))
                .findFirst()
                .orElseThrow();
        Job updated = queue.job(blocked).orElseThrow();
        assertEquals(JobState.PENDING, updated.state());
        assertTrue(updated.after().contains(remedy.id()), "the blocked job depends on the prerequisite");
        assertEquals(remedy.id(), QueuePolicy.next(queue, FACTS).orElseThrow().id());

        queue.finish(remedy.id(), "done");
        assertEquals(blocked, QueuePolicy.next(queue, FACTS).orElseThrow().id());
    }

    @Test
    void aRunningJobCannotBeRewritten() {
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        long job = queue.addDirect(step("place_block")).id();
        queue.markRunning(job);
        assertTrue(queue.insertPrerequisite(job, step("obtain_item")).text().contains("running"));
        assertEquals(1, queue.jobCount());
    }

    private static long insert(JobQueue queue, long blocked) {
        assertTrue(queue.insertPrerequisite(blocked, step("obtain_item")).ok());
        return queue.jobs().stream()
                .filter(job -> job.planId() == 0 && job.tool().equals("obtain_item"))
                .mapToLong(Job::id)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void aSuccessfulPrerequisiteReleasesItsDependentWhenItLeaves() {
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        JobQueue.Added added = queue.addPlan("p", List.of(step("place_block")), PlanOptions.DEFAULT);
        long blocked = added.jobIds().getFirst();
        long remedy = insert(queue, blocked);

        queue.markRunning(remedy);
        queue.finish(remedy, "done");
        assertTrue(queue.remove(remedy).ok());

        assertTrue(queue.job(remedy).isEmpty(), "a successful prerequisite leaves the queue");
        assertTrue(queue.job(blocked).orElseThrow().after().isEmpty(), "the satisfied edge is released");
        assertEquals(PlanStatus.ACTIVE, queue.planStatus(added.planId()));
        assertEquals(blocked, QueuePolicy.next(queue, FACTS).orElseThrow().id());
    }

    @Test
    void aFailedPrerequisiteStaysAsTheNamedBlocker() {
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        JobQueue.Added added = queue.addPlan("p", List.of(step("place_block")), PlanOptions.DEFAULT);
        long blocked = added.jobIds().getFirst();
        long remedy = insert(queue, blocked);

        queue.markRunning(remedy);
        queue.failPermanently(remedy, "nothing nearby");
        assertTrue(queue.remove(remedy).ok(), "the driver asks to drop the settled remedy");

        Job kept = queue.job(remedy).orElseThrow();
        assertEquals(JobState.FAILED, kept.state(), "a failed remedy keeps the row the dependent names");
        assertTrue(kept.result().contains("nothing nearby"));
        assertEquals(PlanStatus.NEEDS_DECISION, queue.planStatus(added.planId()));
        assertEquals(
                "depends on #" + remedy + ", which failed",
                QueuePolicy.notReadyReason(queue, queue.job(blocked).orElseThrow(), FACTS));
    }

    @Test
    void aCancelledPrerequisiteKeepsItsBlockerUntilThePlanEnds() {
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        JobQueue.Added added = queue.addPlan("p", List.of(step("place_block")), PlanOptions.DEFAULT);
        long blocked = added.jobIds().getFirst();
        long remedy = insert(queue, blocked);

        queue.cancel(remedy, "stopped by the owner");
        assertTrue(queue.remove(remedy).ok());
        assertEquals(JobState.CANCELLED, queue.job(remedy).orElseThrow().state());
        assertEquals(PlanStatus.NEEDS_DECISION, queue.planStatus(added.planId()));
        assertEquals(
                "depends on #" + remedy + ", which was cancelled",
                QueuePolicy.notReadyReason(queue, queue.job(blocked).orElseThrow(), FACTS));

        queue.archivePlan(added.planId(), false, "abandoned");
        assertTrue(queue.job(remedy).isEmpty(), "the blocker leaves with the plan that named it");
    }

    @Test
    void aMissingDependencyIsNamedAsSuch() {
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        Job orphan = new Job(
                1,
                1,
                "place_block",
                new JsonObject(),
                0,
                false,
                100,
                JobState.PENDING,
                List.of(),
                GuardAction.WAIT,
                FailurePolicy.ASK,
                1,
                0,
                List.of(99L),
                "",
                0);
        assertEquals("depends on #99, which is missing", QueuePolicy.notReadyReason(queue, orphan, FACTS));
    }
}
