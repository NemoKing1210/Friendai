package dev.friendai.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
