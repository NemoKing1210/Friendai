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

    @Test
    void structuredInspectionShowsSelectedBranchPathAndRepeatProgress() {
        PlanDefinition definition = new PlanDefinition(List.of(new PlanNode.Conditional(new Guard.FluidAhead(),
                List.of(new PlanNode.Repeat(List.of(new PlanNode.Action(new QueuedStep("go_to", new JsonObject(), null,
                        List.of(), GuardAction.WAIT, null, 1, List.of()))), new Guard.TimeElapsed(100), 10)), List.of())));
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        long plan = queue.addStructuredPlan("travel", definition, PlanOptions.DEFAULT);
        queue.updatePlanCursor(plan, new PlanCursor(List.of(new PlanCursor.Frame(List.of(), 0, "", "", false)),
                Map.of("step 1", true), Map.of("step 1 → then #1", 3), 0, 0, "", null));

        String shown = QueueText.show(queue, FACTS, null, 20, false);
        assertTrue(shown.contains("step 1 → then #1"), shown);
        assertTrue(shown.contains("selected then"), shown);
        assertTrue(shown.contains("3/10"), shown);
        assertTrue(shown.contains("tree: [if("), shown);
        assertTrue(shown.contains("repeat("), shown);
        assertTrue(shown.contains("go_to"), shown);
        var rows = dev.friendai.debug.QueueRows.build(queue, FACTS, null);
        assertTrue(rows.stream().anyMatch(row -> row.planId() == plan && row.note().contains("3/10")));
    }
}
