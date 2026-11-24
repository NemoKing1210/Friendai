package dev.friendai.server.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.task.TaskState;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RetryCheckpointsTest {
    private static TaskState state(String data) {
        return new TaskState("move_items", data);
    }

    @Test
    void keepsProgressForTheJobArgumentsItBelongsTo() {
        RetryCheckpoints checkpoints = new RetryCheckpoints();
        checkpoints.keep(7, "{\"count\":5}", state("STORE"));

        RetryCheckpoints.Entry entry = checkpoints.take(7, "{\"count\":5}").orElseThrow();
        assertEquals("STORE", entry.state().data());
        assertTrue(checkpoints.snapshot().isEmpty(), "taking consumes the entry");
    }

    @Test
    void changedArgumentsInvalidateTheSavedProgress() {
        RetryCheckpoints checkpoints = new RetryCheckpoints();
        checkpoints.keep(7, "{\"count\":5}", state("STORE"));

        Optional<RetryCheckpoints.Entry> stale = checkpoints.take(7, "{\"count\":2}");

        assertTrue(stale.isEmpty(), "a checkpoint describes the old request");
        assertTrue(checkpoints.snapshot().isEmpty(), "the stale entry is dropped, not kept");
    }

    @Test
    void progressMovesToTheJobARetryQueued() {
        RetryCheckpoints checkpoints = new RetryCheckpoints();
        checkpoints.keep(7, "{\"count\":5}", state("STORE"));

        assertTrue(checkpoints.carry(7, 8, "{\"count\":5}"));
        assertTrue(checkpoints.peek(7).isEmpty(), "the old job no longer owns the progress");
        assertEquals("STORE", checkpoints.peek(8).orElseThrow().state().data());
        assertFalse(checkpoints.carry(9, 10, "{}"), "nothing to carry is not a failure worth an entry");
    }

    @Test
    void theBoundDropsTheOldestEntryAndKeepsTheRest() {
        RetryCheckpoints checkpoints = new RetryCheckpoints();
        for (int job = 1; job <= RetryCheckpoints.MAX_ENTRIES + 2; job++) {
            checkpoints.keep(job, "{}", state("state-" + job));
        }

        assertEquals(RetryCheckpoints.MAX_ENTRIES, checkpoints.size());
        assertTrue(checkpoints.peek(1).isEmpty(), "the oldest entry is the one dropped");
        assertEquals(
                RetryCheckpoints.MAX_ENTRIES + 2,
                checkpoints.snapshot().keySet().stream()
                        .mapToLong(Long::longValue)
                        .max()
                        .orElse(0));
    }

    @Test
    void restoringPrunesToTheJobsThatStillExist() {
        RetryCheckpoints checkpoints = new RetryCheckpoints();
        checkpoints.keep(1, "{}", state("one"));
        checkpoints.keep(2, "{}", state("two"));
        RetryCheckpoints restored = new RetryCheckpoints();
        restored.restore(checkpoints.snapshot());
        List<Long> live = new ArrayList<>(List.of(2L));

        restored.retainOnly(live);

        assertEquals(1, restored.size());
        assertTrue(restored.peek(1).isEmpty());
        assertTrue(restored.peek(2).isPresent());
    }

    @Test
    void oversizedArgumentsAreNotRemembered() {
        RetryCheckpoints checkpoints = new RetryCheckpoints();
        checkpoints.keep(3, "x".repeat(RetryCheckpoints.MAX_ARGS_CHARS + 1), state("STORE"));
        assertEquals(0, checkpoints.size());
    }

    @Test
    void clearingDropsEverything() {
        RetryCheckpoints checkpoints = new RetryCheckpoints();
        checkpoints.keep(1, "{}", state("one"));
        checkpoints.clear();
        assertEquals(0, checkpoints.size());
    }
}
