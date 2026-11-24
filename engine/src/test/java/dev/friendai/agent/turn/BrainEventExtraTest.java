package dev.friendai.agent.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.execution.TaskDisposition;
import dev.friendai.execution.TaskWorkResult;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BrainEventExtraTest {
    @Test
    void oldConstructorsKeepTheExactLines() {
        assertEquals(
                "[event] task #7 mine_blocks failed: no pickaxe",
                new BrainEvent.TaskFinished(7, "mine_blocks", false, "no pickaxe").toPromptLine());
        assertEquals(
                "[event] plan #2 house finished: done",
                new BrainEvent.PlanFinished(2, "house", true, "done").toPromptLine());
        assertEquals(
                "[event] plan #2 needs a decision at job #5 (go_to): can't reach 1 2 3; 3 step(s) remain. "
                        + "Use queue_edit to repair the plan.",
                new BrainEvent.PlanBlocked(2, "house", 5, "go_to", "can't reach 1 2 3", 3).toPromptLine());
    }

    @Test
    void aLowerBoundSaysAtLeast() {
        assertEquals(
                "[event] plan #2 needs a decision at job #5 (go_to): can't reach 1 2 3; "
                        + "at least 2 step(s) remain. Use queue_edit to repair the plan.",
                new BrainEvent.PlanBlocked(2, "house", 5, "go_to", "can't reach 1 2 3", 2, false, "").toPromptLine());
    }

    @Test
    void aReplanNamesThePlanAndItsReason() {
        assertEquals(
                "[event] replan stalled plan #2: missing pickaxe. Keep the plan's completed work and repair only what remains.",
                new BrainEvent.Replan(2, "missing pickaxe").toPromptLine());
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> new BrainEvent.Replan(0, "x"));
    }

    @Test
    void extraTextIsAppendedAfterTheLine() {
        assertEquals(
                "[event] task #7 mine_blocks failed: no pickaxe facts: -3 minecraft:cobblestone",
                new BrainEvent.TaskFinished(7, "mine_blocks", false, "no pickaxe", "facts: -3 minecraft:cobblestone")
                        .toPromptLine());
        assertEquals(
                "[event] plan #2 house failed: boom diagnosis: UNKNOWN x",
                new BrainEvent.PlanFinished(2, "house", false, "boom", "diagnosis: UNKNOWN x").toPromptLine());
    }

    @Test
    void blankExtraAddsNothing() {
        assertEquals(
                "[event] task #1 stay finished: ok",
                new BrainEvent.TaskFinished(1, "stay", true, "ok", "  ").toPromptLine());
    }

    @Test
    void aPlanBoundaryCarriesWhatTheStepMeasured() {
        TaskWorkResult receipt = new TaskWorkResult(
                List.of(new TaskWorkResult.Line("minecraft:wheat", 8, 3, TaskWorkResult.Evidence.HANDED_OVER)),
                "partial",
                "only gave 3 of 8 minecraft:wheat",
                "retry the remaining share");

        String blocked = new BrainEvent.PlanBlocked(
                        2,
                        "store the wheat",
                        5,
                        "give_items",
                        "owner is too far away",
                        3,
                        true,
                        "",
                        Optional.of(receipt))
                .toPromptLine();
        assertTrue(blocked.endsWith(receipt.compact()), blocked);
        assertTrue(blocked.contains("minecraft:wheat handed_over=3/8 remaining=5"), blocked);
        assertTrue(blocked.contains("job #5"), "the receipt keeps the failed step next to it: " + blocked);

        String finished = new BrainEvent.PlanFinished(
                        2, "store the wheat", TaskDisposition.FAILED, "blocked", "", Optional.of(receipt))
                .toPromptLine();
        assertTrue(finished.endsWith(receipt.compact()), finished);
    }

    @Test
    void aPlanBoundaryWithoutAReceiptKeepsItsLine() {
        String line =
                new BrainEvent.PlanBlocked(2, "house", 5, "go_to", "can't reach 1 2 3", 3, true, "").toPromptLine();
        assertEquals(
                "[event] plan #2 needs a decision at job #5 (go_to): can't reach 1 2 3; 3 step(s) remain. "
                        + "Use queue_edit to repair the plan.",
                line);
        assertEquals(
                "[event] plan #2 house finished: done",
                new BrainEvent.PlanFinished(2, "house", true, "done", "").toPromptLine());
    }
}
