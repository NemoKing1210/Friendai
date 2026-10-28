package dev.friendai.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
