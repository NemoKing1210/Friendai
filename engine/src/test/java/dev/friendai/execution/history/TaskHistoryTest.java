package dev.friendai.execution.history;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.friendai.execution.TaskDisposition;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskHistoryTest {
    @Test
    void newestFirstAndBounded() {
        TaskHistory history = new TaskHistory(2);
        history.add(new TaskRecord(1, "stay", "SUCCESS", "a"));
        history.add(new TaskRecord(2, "go_to", "FAILED", "b"));
        history.add(new TaskRecord(3, "mine_blocks", "CANCELLED", "c"));
        assertEquals(
                List.of(3, 2), history.recent().stream().map(TaskRecord::id).toList());
    }

    @Test
    void recordsKeepTypedDispositionAndLegacyStatusText() {
        TaskRecord cancelled = new TaskRecord(4, "mine_blocks", TaskDisposition.CANCELLED, "owner stopped it");
        TaskRecord timedOut = new TaskRecord(5, "go_to", TaskDisposition.TIMED_OUT, "timed out");

        assertEquals(TaskDisposition.CANCELLED, cancelled.disposition());
        assertEquals("CANCELLED", cancelled.status());
        assertEquals(TaskDisposition.TIMED_OUT, timedOut.disposition());
        assertEquals("TIMED_OUT", timedOut.status());
        assertEquals(TaskDisposition.FAILED, new TaskRecord(6, "mine_blocks", "FAILED", "blocked").disposition());
    }
}
