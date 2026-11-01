package dev.friendai.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TaskProgressTest {
    @Test
    void aMissingDetailAndToolBecomeEmpty() {
        TaskProgress progress = new TaskProgress(3, null, TaskPhase.STARTED, -1, null);
        assertEquals("", progress.tool());
        assertEquals("", progress.detail());
    }

    @Test
    void aDetailThatAlreadyFitsIsReused() {
        String detail = "x".repeat(TaskProgress.MAX_DETAIL);
        assertSame(detail, new TaskProgress(1, "mine_blocks", TaskPhase.PROGRESS, 10, detail).detail());
    }

    @Test
    void aLongDetailIsTruncatedToTheBound() {
        TaskProgress progress = new TaskProgress(1, "mine_blocks", TaskPhase.PROGRESS, 10, "a".repeat(400));
        assertEquals(TaskProgress.MAX_DETAIL, progress.detail().length());
        assertTrue(progress.detail().endsWith("\u2026"));
    }

    @Test
    void truncationNeverSplitsASurrogatePair() {
        // A supplementary character occupies two chars, so a plain substring would cut it in half.
        String detail = "a".repeat(TaskProgress.MAX_DETAIL - 2) + "\uD83D\uDE00" + "tail";
        TaskProgress progress = new TaskProgress(1, "mine_blocks", TaskPhase.PROGRESS, 10, detail);
        String bounded = progress.detail();
        assertTrue(bounded.length() <= TaskProgress.MAX_DETAIL);
        assertTrue(bounded.endsWith("\u2026"));
        assertFalse(Character.isHighSurrogate(bounded.charAt(bounded.length() - 2)));
    }

    @Test
    void lineBreaksCollapseIntoOneLine() {
        TaskProgress progress = new TaskProgress(1, "go_to", TaskPhase.WAITING, -1, "waiting for\r\nthe door\tto open");
        assertEquals("waiting for the door to open", progress.detail());
    }

    @Test
    void percentIsClampedToTheContract() {
        assertEquals(100, progress(TaskPhase.PROGRESS, 140).percent());
        assertEquals(100, progress(TaskPhase.PROGRESS, 100).percent());
        assertEquals(0, progress(TaskPhase.PROGRESS, 0).percent());
        assertEquals(-1, progress(TaskPhase.PROGRESS, -7).percent());
        assertEquals(42, progress(TaskPhase.PROGRESS, 42).percent());
    }

    @Test
    void onlyANonNegativePercentCountsAsKnown() {
        assertTrue(progress(TaskPhase.PROGRESS, 0).percentKnown());
        assertFalse(progress(TaskPhase.PROGRESS, -1).percentKnown());
    }

    @Test
    void everyPhaseIsTerminalOnlyWhenFinished() {
        for (TaskPhase phase : TaskPhase.values()) {
            assertEquals(phase == TaskPhase.FINISHED, phase.terminal());
        }
    }

    @Test
    void aPhaseIsRequired() {
        assertThrows(IllegalArgumentException.class, () -> new TaskProgress(1, "mine_blocks", null, 5, "working"));
    }

    private static TaskProgress progress(TaskPhase phase, int percent) {
        return new TaskProgress(1, "mine_blocks", phase, percent, "working");
    }
}
