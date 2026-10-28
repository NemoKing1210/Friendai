package dev.friendai.server.execution.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.friendai.queue.FailurePolicy;
import dev.friendai.queue.GuardAction;
import dev.friendai.queue.JobQueue;
import dev.friendai.queue.QueueLimits;
import dev.friendai.queue.QueueSave;
import dev.friendai.queue.QueuedStep;
import dev.friendai.task.TaskState;
import java.util.List;
import org.junit.jupiter.api.Test;

class SavedExecutionCodecTest {
    @Test
    void roundTripsQueueAndTypedActiveTaskCheckpoint() {
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        long plan = queue.addPlan(
                        "gather",
                        List.of(new QueuedStep(
                                "mine_blocks",
                                new JsonObject(),
                                null,
                                List.of(),
                                GuardAction.WAIT,
                                FailurePolicy.ASK,
                                1,
                                List.of())),
                        dev.friendai.queue.PlanOptions.DEFAULT)
                .planId();
        long active = queue.jobsOfPlan(plan).getFirst().id();
        queue.markRunning(active);
        SavedExecution saved = new SavedExecution(
                SavedExecution.CURRENT_VERSION,
                new QueueSave.Decoded(queue, active),
                new TaskState("mine_blocks", "checkpoint"));

        String encoded = SavedExecutionCodec.encode(saved);
        SavedExecution decoded =
                SavedExecutionCodec.decode(encoded, QueueLimits.DEFAULTS).orElseThrow();

        assertTrue(encoded.contains("\"version\":1"));
        assertFalse(encoded.contains("queue:v1:"));
        assertFalse(encoded.contains("queue:v2:"));
        assertEquals("gather", decoded.queue().queue().plan(plan).orElseThrow().goal());
        assertEquals(active, decoded.queue().activeJobId());
        assertEquals(saved.activeTask(), decoded.activeTask());
    }

    @Test
    void rejectsMalformedTruncatedAndFutureDocuments() {
        assertTrue(SavedExecutionCodec.decode("{", QueueLimits.DEFAULTS).isEmpty());
        assertTrue(SavedExecutionCodec.decode("{\"version\":99}", QueueLimits.DEFAULTS)
                .isEmpty());
        assertTrue(SavedExecutionCodec.decode("{}", QueueLimits.DEFAULTS).isEmpty());
    }

    @Test
    void boundsTaskStateAndChunkCount() {
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        SavedExecution saved = new SavedExecution(
                SavedExecution.CURRENT_VERSION,
                new QueueSave.Decoded(queue, 0),
                new TaskState("stay", "x".repeat(8_192)));
        var parts = SavedExecutionCodec.split(SavedExecutionCodec.encode(saved));
        assertTrue(parts.size() <= SavedExecutionCodec.MAX_PARTS);
        assertTrue(SavedExecutionCodec.join(parts).isPresent());
        assertFalse(SavedExecutionCodec.join(List.of("x".repeat(SavedExecutionCodec.CHUNK_CHARS + 1)))
                .isPresent());
        assertFalse(SavedExecutionCodec.join(java.util.Collections.nCopies(SavedExecutionCodec.MAX_PARTS + 1, "x"))
                .isPresent());
    }

    @Test
    void chunksDoNotSplitUnicodeCodePoints() {
        String encoded = "x".repeat(SavedExecutionCodec.CHUNK_CHARS - 1) + "😀" + "y".repeat(40);
        List<String> parts = SavedExecutionCodec.split(encoded);

        assertEquals(encoded, SavedExecutionCodec.join(parts).orElseThrow());
        for (String part : parts) {
            for (int index = 0; index < part.length(); index++) {
                char value = part.charAt(index);
                if (Character.isHighSurrogate(value)) {
                    assertTrue(index + 1 < part.length() && Character.isLowSurrogate(part.charAt(index + 1)));
                    index++;
                } else assertFalse(Character.isLowSurrogate(value));
            }
        }
    }
}
