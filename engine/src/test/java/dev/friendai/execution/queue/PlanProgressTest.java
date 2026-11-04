package dev.friendai.execution.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlanProgressTest {
    private static final GuardFacts FACTS = new GuardFacts() {
        @Override
        public int countItems(String idOrTag) {
            return 0;
        }

        @Override
        public int emptySlots() {
            return 36;
        }

        @Override
        public float healthFraction() {
            return 1f;
        }

        @Override
        public int ownerDistance() {
            return 0;
        }

        @Override
        public int countBlocks(String idOrTag, int radius) {
            return 0;
        }

        @Override
        public String dimension() {
            return "minecraft:overworld";
        }
    };

    private static QueuedStep step(String tool) {
        return new QueuedStep(tool, null, null, List.of(), GuardAction.WAIT, null, 1, null);
    }

    private static PlanDefinition actions(int count) {
        List<PlanNode> nodes = new ArrayList<>();
        for (int i = 0; i < count; i++) nodes.add(new PlanNode.Action(step("tool" + i)));
        return new PlanDefinition(nodes);
    }

    @Test
    void aFlatPlanCountsPendingJobs() {
        assertEquals(new PlanInterpreter.Remaining(3, true), PlanInterpreter.remaining(3));
    }

    @Test
    void aStructuredPlanCountsTheCommittedPath() {
        PlanDefinition plan = actions(3);
        PlanCursor first = PlanInterpreter.advance(
                        plan, PlanCursor.start(1, 0), FACTS, new PlanInterpreter.ActionResult.None())
                .cursor();
        PlanCursor second = PlanInterpreter.advance(plan, first, FACTS, new PlanInterpreter.ActionResult.Succeeded())
                .cursor();

        PlanInterpreter.Remaining remaining = PlanInterpreter.remaining(plan, second, FACTS);

        assertEquals(new PlanInterpreter.Remaining(2, true), remaining, "the committed action and the one after it");
        assertTrue(second.pendingActionPath().contains("step 2"), "the input cursor is left where it was");
    }

    @Test
    void anUndecidableRepeatGuardReportsAtLeast() {
        PlanDefinition plan = new PlanDefinition(List.of(new PlanNode.Repeat(
                List.of(new PlanNode.Action(step("hop"))), new Guard.HasItem("minecraft:oak_log", 1), 3)));

        PlanInterpreter.Remaining remaining = PlanInterpreter.remaining(plan, PlanCursor.start(1, 0), FACTS);

        assertFalse(remaining.exact(), "a repeat the snapshot cannot close is a lower bound");
        assertTrue(remaining.actions() >= 1);
    }

    @Test
    void withoutFactsOnlyLeadingActionsAreCounted() {
        PlanDefinition plan = new PlanDefinition(List.of(
                new PlanNode.Action(step("a")),
                new PlanNode.Conditional(
                        new Guard.HasItem("minecraft:oak_log", 1), List.of(new PlanNode.Action(step("b"))), List.of()),
                new PlanNode.Action(step("c"))));

        PlanInterpreter.Remaining remaining = PlanInterpreter.remaining(plan, PlanCursor.start(1, 0), null);

        assertEquals(new PlanInterpreter.Remaining(1, false), remaining, "stop at the first decision");
    }
}
