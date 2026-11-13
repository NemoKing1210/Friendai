package dev.friendai.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TaskWorkResultTest {
    @Test
    void remainingAndBlockerAreBoundedAndSingleLine() {
        String longBlocker = "2 logs left on the crown\n" + "x".repeat(TaskWorkResult.MAX_BLOCKER_CHARS * 2);
        TaskWorkResult result = new TaskWorkResult(
                List.of(new TaskWorkResult.Line("minecraft:oak_log", 12, 9, TaskWorkResult.Evidence.CARRIED)),
                "partial",
                longBlocker,
                "try the next reachable tree");

        assertEquals(3, result.lines().getFirst().remaining());
        assertEquals(TaskWorkResult.MAX_BLOCKER_CHARS, result.blocker().length());
        assertTrue(result.blocker().startsWith("2 logs left on the crown"));
        assertFalse(result.blocker().contains("\n"));
        assertTrue(result.compact().contains("remaining=3"));
        assertFalse(result.compact().contains("\n"));
        assertTrue(result.compact().length() <= TaskWorkResult.MAX_COMPACT_CHARS);
    }

    @Test
    void oneItemCanKeepHandoverAndPickupEvidenceSeparate() {
        TaskWorkResult result = new TaskWorkResult(
                List.of(
                        new TaskWorkResult.Line("minecraft:stone_pickaxe", 1, 1, TaskWorkResult.Evidence.HANDED_OVER),
                        new TaskWorkResult.Line("minecraft:stone_pickaxe", 1, 0, TaskWorkResult.Evidence.PICKED_UP)),
                "partial",
                "the owner has not picked up the drop",
                "wait for the owner to collect it");

        assertEquals(2, result.lines().size());
        assertEquals(1, result.lines().get(1).remaining());
        assertTrue(result.compact().contains("handed_over=1/1"));
        assertTrue(result.compact().contains("picked_up=0/1"));
    }

    @Test
    void rejectsDuplicateRowsForTheSameItemAndEvidence() {
        TaskWorkResult.Line carried =
                new TaskWorkResult.Line("minecraft:oak_log", 12, 9, TaskWorkResult.Evidence.CARRIED);

        assertThrows(
                IllegalArgumentException.class,
                () -> new TaskWorkResult(List.of(carried, carried), "partial", "short", "try another source"));
    }
}
