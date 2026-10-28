package dev.friendai.execution.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;

class QueuePolicyTest {
    private static final GuardFacts FACTS =
            new FakeGuardFacts(java.util.Map.of(), 10, 1, 0, java.util.Map.of(), "minecraft:overworld");

    private static QueuedStep step(String tool) {
        return new QueuedStep(tool, new JsonObject(), null, List.of(), GuardAction.WAIT, null, 1, List.of());
    }

    @Test
    void nextUsesPriorityThenOrder() {
        JobQueue q = new JobQueue(QueueLimits.DEFAULTS);
        q.addPlan("low", List.of(step("a")), new PlanOptions(0, FailurePolicy.ASK, 0));
        long high = q.addPlan("high", List.of(step("b")), new PlanOptions(5, FailurePolicy.ASK, 0))
                .jobIds()
                .getFirst();
        assertEquals(high, QueuePolicy.next(q, FACTS).orElseThrow().id());
    }

    @Test
    void askFailureNeedsDecisionAndBlocksOnlyThatPlan() {
        JobQueue q = new JobQueue(QueueLimits.DEFAULTS);
        long failed =
                q.addPlan("a", List.of(step("a")), PlanOptions.DEFAULT).jobIds().getFirst();
        long ready =
                q.addPlan("b", List.of(step("b")), PlanOptions.DEFAULT).jobIds().getFirst();
        q.markRunning(failed);
        q.fail(failed, "nope");
        assertEquals(PlanStatus.NEEDS_DECISION, q.planStatus(1));
        assertEquals(ready, QueuePolicy.next(q, FACTS).orElseThrow().id());
        assertTrue(QueuePolicy.notReadyReason(q, q.job(failed).orElseThrow(), FACTS)
                .contains("decision"));
    }
}
