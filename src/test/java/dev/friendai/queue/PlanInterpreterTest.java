package dev.friendai.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlanInterpreterTest {
    @Test
    void selectsBranchOnceAndAdvancesOneActionAtATime() {
        PlanDefinition plan = plan(new PlanNode.Conditional(new Guard.InBiome("minecraft:plains"),
                List.of(action("first"), action("second")), List.of(action("else"))));
        var cursor = PlanCursor.start(10, 20);

        var first = PlanInterpreter.advance(plan, cursor, facts("minecraft:plains", false), new PlanInterpreter.ActionResult.None());
        var emitted = assertInstanceOf(PlanInterpreter.Event.EmitAction.class, first.event());
        assertEquals("step 1 → then #1", emitted.path());
        assertEquals("first", emitted.step().tool());

        var second = PlanInterpreter.advance(plan, first.cursor(), facts("minecraft:desert", false), new PlanInterpreter.ActionResult.Succeeded());
        var next = assertInstanceOf(PlanInterpreter.Event.EmitAction.class, second.event());
        assertEquals("step 1 → then #2", next.path());
        assertEquals("second", next.step().tool());
        assertTrue(second.cursor().selectedBranches().containsValue(true));

        var done = PlanInterpreter.advance(plan, second.cursor(), facts("minecraft:desert", false), new PlanInterpreter.ActionResult.Succeeded());
        assertInstanceOf(PlanInterpreter.Event.Complete.class, done.event());
    }

    @Test
    void trueUntilSkipsBodyAndUnmetLoopFailsAtItsCap() {
        PlanDefinition plan = plan(new PlanNode.Repeat(List.of(action("work")), new Guard.FluidAhead(), 2));
        var skipped = PlanInterpreter.advance(plan, PlanCursor.start(0, 0), facts("minecraft:plains", true), new PlanInterpreter.ActionResult.None());
        assertInstanceOf(PlanInterpreter.Event.Complete.class, skipped.event());

        var first = PlanInterpreter.advance(plan, PlanCursor.start(0, 0), facts("minecraft:plains", false), new PlanInterpreter.ActionResult.None());
        assertEquals("step 1 → repeat → step 1", assertInstanceOf(PlanInterpreter.Event.EmitAction.class, first.event()).path());
        var second = PlanInterpreter.advance(plan, first.cursor(), facts("minecraft:plains", false), new PlanInterpreter.ActionResult.Succeeded());
        assertInstanceOf(PlanInterpreter.Event.EmitAction.class, second.event());
        var failed = PlanInterpreter.advance(plan, second.cursor(), facts("minecraft:plains", false), new PlanInterpreter.ActionResult.Succeeded());
        var failure = assertInstanceOf(PlanInterpreter.Event.Failed.class, failed.event());
        assertEquals("step 1 → repeat → until", failure.path());
    }

    @Test
    void actionFailureIsTerminalAndCarriesNestedPath() {
        PlanDefinition plan = plan(new PlanNode.Conditional(new Guard.FluidAhead(), List.of(action("work")), List.of()));
        var emitted = PlanInterpreter.advance(plan, PlanCursor.start(5, 3), facts("minecraft:plains", true), new PlanInterpreter.ActionResult.None());
        var failed = PlanInterpreter.advance(plan, emitted.cursor(), facts("minecraft:plains", true), new PlanInterpreter.ActionResult.Failed("protected"));
        var failure = assertInstanceOf(PlanInterpreter.Event.Failed.class, failed.event());
        assertEquals("step 1 → then #1", failure.path());
        assertTrue(failure.reason().contains("protected"));
    }

    private static PlanDefinition plan(PlanNode node) { return new PlanDefinition(List.of(node)); }
    private static PlanNode.Action action(String tool) {
        return new PlanNode.Action(new QueuedStep(tool, new JsonObject(), null, List.of(), GuardAction.WAIT, null, 1, List.of()));
    }
    private static GuardFacts facts(String biome, boolean fluid) {
        return new GuardFacts() {
            public int countItems(String id) { return 0; }
            public int emptySlots() { return 0; }
            public float healthFraction() { return 1; }
            public int ownerDistance() { return 0; }
            public int countBlocks(String id, int radius) { return 0; }
            public String dimension() { return "minecraft:overworld"; }
            public String biome() { return biome; }
            public boolean fluidAhead() { return fluid; }
        };
    }
}
