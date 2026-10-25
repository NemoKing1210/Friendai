package dev.friendai.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;

class QueueOptimizerTest {
    private static final MergePolicy COUNTS = tool -> "mine_blocks".equals(tool) ? "count" : null;
    private static QueuedStep mine(int count) {
        JsonObject args = new JsonObject(); args.addProperty("count", count);
        return new QueuedStep("mine_blocks", args, null, List.of(), GuardAction.WAIT, null, 1, List.of());
    }
    @Test void mergesAdjacentCountSteps() {
        JobQueue q = new JobQueue(QueueLimits.DEFAULTS);
        q.addPlan("mine", List.of(mine(3), mine(6)), PlanOptions.DEFAULT);
        assertTrue(QueueOptimizer.apply(q, null, COUNTS).text().contains("count=9"));
        assertEquals(1, q.jobCount());
        assertEquals(9, q.jobs().getFirst().args().get("count").getAsInt());
    }
    @Test void doesNotMergeDifferentToolsOrPastSixtyFour() {
        JobQueue q = new JobQueue(QueueLimits.DEFAULTS);
        q.addPlan("mine", List.of(mine(60), mine(5)), PlanOptions.DEFAULT);
        assertEquals("nothing to merge", QueueOptimizer.apply(q, null, COUNTS).text());
        assertEquals(2, q.jobCount());
    }
}
