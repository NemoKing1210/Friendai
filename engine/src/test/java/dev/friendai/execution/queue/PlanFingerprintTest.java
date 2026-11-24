package dev.friendai.execution.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlanFingerprintTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID GUEST = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    private static QueuedStep step(String tool) {
        return new QueuedStep(tool, new JsonObject(), null, List.of(), GuardAction.WAIT, null, 1, List.of());
    }

    private static QueuedStep step(String tool, UUID principal) {
        return new QueuedStep(tool, new JsonObject(), null, List.of(), GuardAction.WAIT, null, 1, List.of(), principal);
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
    void reorderingTheSameJobsInvalidatesTheToken() {
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        JobQueue.Added plan = queue.addPlan("gather", List.of(step("a"), step("b")), PlanOptions.DEFAULT);
        String fingerprint = PlanFingerprint.of(queue, plan.planId());

        queue.move(plan.jobIds().get(1), plan.jobIds().getFirst());

        assertNotEquals(
                fingerprint,
                PlanFingerprint.of(queue, plan.planId()),
                "the same jobs in a different execution order are different work");
    }

    @Test
    void aPriorityChangeInvalidatesTheToken() {
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        JobQueue.Added plan = queue.addPlan("gather", List.of(step("a"), step("b")), PlanOptions.DEFAULT);
        String fingerprint = PlanFingerprint.of(queue, plan.planId());

        queue.setPriority(plan.jobIds().get(1), 5);

        assertNotEquals(
                fingerprint,
                PlanFingerprint.of(queue, plan.planId()),
                "a step that now runs sooner is a different plan");
    }

    @Test
    void aDifferentRequesterWithTheSameArgumentsInvalidatesTheToken() {
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        JobQueue.Added plan = queue.addPlan("gather", List.of(step("a", OWNER), step("b", OWNER)), PlanOptions.DEFAULT);
        String fingerprint = PlanFingerprint.of(queue, plan.planId());

        queue.updateArgs(plan.jobIds().get(1), new JsonObject(), GUEST, false);

        assertNotEquals(
                fingerprint,
                PlanFingerprint.of(queue, plan.planId()),
                "work that would now run under another principal is not the work the proposal described");
    }

    @Test
    void aMissingPlanStillHasAStableToken() {
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        String token = PlanFingerprint.of(queue, 99);
        assertEquals(token, PlanFingerprint.of(queue, 99));
        assertEquals(PlanFingerprint.LENGTH, token.length());
    }
}
