package dev.friendai.execution.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlanPatchTest {
    private static QueuedStep step(String tool) {
        return new QueuedStep(tool, new JsonObject(), null, List.of(), GuardAction.WAIT, null, 1, List.of());
    }

    private static PlanNode action(String tool) {
        return new PlanNode.Action(step(tool));
    }

    @Test
    void aRootRangeIsReplacedInPlace() {
        PlanDefinition plan = new PlanDefinition(List.of(action("a"), action("b"), action("c")));
        PlanDefinition patched = PlanPatch.replace(plan, List.of(), 1, 1, List.of(action("x"), action("y")));
        assertEquals(
                List.of("a", "x", "y", "c"),
                patched.nodes().stream()
                        .map(node -> ((PlanNode.Action) node).step().tool())
                        .toList());
    }

    @Test
    void aConditionalBranchIsPatched() {
        PlanDefinition plan = new PlanDefinition(
                List.of(new PlanNode.Conditional(new Guard.OwnerOnline(), List.of(action("a")), List.of(action("b")))));
        PlanDefinition patched = PlanPatch.replace(plan, List.of(0, 1), 0, 1, List.of(action("x")));
        var conditional = (PlanNode.Conditional) patched.nodes().getFirst();
        assertEquals(
                "x",
                ((PlanNode.Action) conditional.thenNodes().getFirst()).step().tool());
        assertEquals(
                "b",
                ((PlanNode.Action) conditional.elseNodes().getFirst()).step().tool());
    }

    @Test
    void aRepeatBodyIsPatched() {
        PlanDefinition plan =
                new PlanDefinition(List.of(new PlanNode.Repeat(List.of(action("a")), new Guard.OwnerOnline(), 2)));
        PlanDefinition patched = PlanPatch.replace(plan, List.of(0, 3), 0, 1, List.of(action("x")));
        var repeat = (PlanNode.Repeat) patched.nodes().getFirst();
        assertEquals("x", ((PlanNode.Action) repeat.body().getFirst()).step().tool());
        assertEquals(2, repeat.maxIterations());
    }
}
