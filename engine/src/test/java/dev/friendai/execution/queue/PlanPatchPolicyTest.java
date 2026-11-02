package dev.friendai.execution.queue;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlanPatchPolicyTest {
    private static QueuedStep step() {
        return new QueuedStep("wait", new JsonObject(), null, List.of(), GuardAction.WAIT, null, 1, List.of());
    }

    @Test
    void aFlatPendingStepIsPatchable() {
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        long plan = queue.addPlan("p", List.of(step()), PlanOptions.DEFAULT).planId();
        long job = queue.jobsOfPlan(plan).getFirst().id();
        assertTrue(PlanPatchPolicy.reject(queue, job).isEmpty());
    }

    @Test
    void anActiveStructuredFrameIsNotRewrittenBehindTheCursor() {
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        var definition = new PlanDefinition(List.of(new PlanNode.Action(step())));
        long plan = queue.addStructuredPlan("p", definition, PlanOptions.DEFAULT);
        long job = queue.addJob(plan, step()).id();
        assertTrue(PlanPatchPolicy.reject(queue, job).orElseThrow().contains("active structured frame"));
    }

    @Test
    void terminalAndUnknownJobsAreRejected() {
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        long job = queue.addDirect(step()).id();
        queue.finish(job, "done");
        assertTrue(PlanPatchPolicy.reject(queue, job).orElseThrow().contains("terminal"));
        assertTrue(PlanPatchPolicy.reject(queue, 999).orElseThrow().contains("unknown"));
    }
}
