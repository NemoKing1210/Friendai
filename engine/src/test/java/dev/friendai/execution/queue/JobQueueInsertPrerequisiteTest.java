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
}
