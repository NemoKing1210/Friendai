package dev.friendai.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QueueTextTest {
    private static final GuardFacts FACTS = new FakeGuardFacts(Map.of(), 10, 1, 0, Map.of(), "minecraft:overworld");
    @Test void emptyQueue() {
        JobQueue q = new JobQueue(QueueLimits.DEFAULTS);
        assertEquals("queue: empty", QueueText.show(q, FACTS, null, 20, false));
        assertEquals("Task queue: empty", QueueText.summary(q, FACTS, null));
    }
    @Test void summaryNamesRunningAndBlockedWork() {
        JobQueue q = new JobQueue(QueueLimits.DEFAULTS);
        var steps = List.of(new QueuedStep("mine_blocks", new JsonObject(), null, List.of(), GuardAction.WAIT, null, 1, List.of()),
                new QueuedStep("place_block", new JsonObject(), null, List.of(new Guard.HasItem("minecraft:iron_ingot", 3)), GuardAction.WAIT, null, 1, List.of()));
        long active = q.addPlan("build", steps, PlanOptions.DEFAULT).jobIds().getFirst(); q.markRunning(active);
        String text = QueueText.summary(q, FACTS, "mining stone");
        assertTrue(text.contains("Running: #" + active)); assertTrue(text.contains("Blocked: #")); assertTrue(text.contains("needs 3 minecraft:iron_ingot"));
    }
}
