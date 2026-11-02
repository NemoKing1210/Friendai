package dev.friendai.execution.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlanFingerprintTest {
    private static QueuedStep step(String tool) {
        return new QueuedStep(tool, new JsonObject(), null, List.of(), GuardAction.WAIT, null, 1, List.of());
    }

    @Test
    void theTokenIsStableUntilThisPlansWorkChanges() {
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        JobQueue.Added plan = queue.addPlan("gather", List.of(step("a"), step("b")), PlanOptions.DEFAULT);
        String fingerprint = PlanFingerprint.of(queue, plan.planId());
        assertEquals(PlanFingerprint.LENGTH, fingerprint.length());
        assertEquals(fingerprint, PlanFingerprint.of(queue, plan.planId()));

        queue.addPlan("other", List.of(step("c")), PlanOptions.DEFAULT);
        assertEquals(fingerprint, PlanFingerprint.of(queue, plan.planId()), "another plan does not invalidate it");

        queue.markRunning(plan.jobIds().getFirst());
        assertNotEquals(fingerprint, PlanFingerprint.of(queue, plan.planId()));
    }

    @Test
    void aMissingPlanStillHasAStableToken() {
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        String token = PlanFingerprint.of(queue, 99);
        assertEquals(token, PlanFingerprint.of(queue, 99));
        assertEquals(PlanFingerprint.LENGTH, token.length());
    }
}
