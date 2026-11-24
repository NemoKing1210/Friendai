package dev.friendai.server.execution.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.friendai.execution.queue.FailurePolicy;
import dev.friendai.execution.queue.GuardAction;
import dev.friendai.execution.queue.JobQueue;
import dev.friendai.execution.queue.QueueLimits;
import dev.friendai.execution.queue.QueueSave;
import dev.friendai.execution.queue.QueuedStep;
import dev.friendai.task.TaskState;
import java.util.List;
import java.util.Map;
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
                        dev.friendai.execution.queue.PlanOptions.DEFAULT)
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

        assertTrue(encoded.contains("\"version\":3"));
        assertFalse(encoded.contains("queue:v1:"));
        assertFalse(encoded.contains("queue:v2:"));
        assertEquals("gather", decoded.queue().queue().plan(plan).orElseThrow().goal());
        assertEquals(active, decoded.queue().activeJobId());
        assertEquals(saved.activeTask(), decoded.activeTask());
        assertFalse(decoded.manualPause(), "an unpaused save stays unpaused");
    }

    @Test
    void keepsTheOwnerPauseAndStillLoadsADocumentThatPredatesIt() {
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        String paused = SavedExecutionCodec.encode(new SavedExecution(new QueueSave.Decoded(queue, 0), null, true));
        assertTrue(paused.contains("\"manualPause\":true"));
        SavedExecution decoded =
                SavedExecutionCodec.decode(paused, QueueLimits.DEFAULTS).orElseThrow();
        assertTrue(decoded.manualPause(), "the pause survives the round trip");
        assertEquals(SavedExecution.CURRENT_VERSION, decoded.version());

        String legacy = SavedExecutionCodec.encode(new SavedExecution(new QueueSave.Decoded(queue, 0), null, false))
                .replace("\"version\":3", "\"version\":1");
        SavedExecution older =
                SavedExecutionCodec.decode(legacy, QueueLimits.DEFAULTS).orElseThrow();
        assertFalse(older.manualPause(), "a document written before the pause existed cannot claim one");
        assertEquals(SavedExecution.CURRENT_VERSION, older.version(), "the older document is read at the new shape");
    }

    @Test
    void roundTripsTheProgressARetryResumes() {
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        long plan = queue.addPlan(
                        "gather",
                        List.of(new QueuedStep(
                                "move_items",
                                new JsonObject(),
                                null,
                                List.of(),
                                GuardAction.WAIT,
                                FailurePolicy.ASK,
                                2,
                                List.of())),
                        dev.friendai.execution.queue.PlanOptions.DEFAULT)
                .planId();
        long job = queue.jobsOfPlan(plan).getFirst().id();
        SavedExecution saved = new SavedExecution(
                new QueueSave.Decoded(queue, 0),
                null,
                false,
                Map.of(
                        job,
                        new dev.friendai.server.execution.RetryCheckpoints.Entry(
                                "{\"count\":5}", new TaskState("move_items", "STORE"))));

        SavedExecution decoded = SavedExecutionCodec.decode(SavedExecutionCodec.encode(saved), QueueLimits.DEFAULTS)
                .orElseThrow();

        assertEquals(1, decoded.retryProgress().size());
        var entry = decoded.retryProgress().get(job);
        assertEquals("{\"count\":5}", entry.args());
        assertEquals(new TaskState("move_items", "STORE"), entry.state());

        SavedExecution blocked = new SavedExecution(
                new QueueSave.Decoded(queue, 0),
                null,
                false,
                Map.of(job, new dev.friendai.server.execution.RetryCheckpoints.Entry("{}", null)));
        SavedExecution read = SavedExecutionCodec.decode(SavedExecutionCodec.encode(blocked), QueueLimits.DEFAULTS)
                .orElseThrow();
        assertFalse(read.retryProgress().get(job).resumable(), "a blocked attempt stays blocked");
    }

    @Test
    void aDocumentWithoutRetryProgressStillLoads() {
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        String versionTwo = SavedExecutionCodec.encode(new SavedExecution(new QueueSave.Decoded(queue, 0), null, false))
                .replace("\"version\":3", "\"version\":2");
        SavedExecution older =
                SavedExecutionCodec.decode(versionTwo, QueueLimits.DEFAULTS).orElseThrow();
        assertTrue(older.retryProgress().isEmpty());
        assertEquals(SavedExecution.CURRENT_VERSION, older.version());
    }

    @Test
    void rejectsMalformedTruncatedAndFutureDocuments() {
        assertTrue(SavedExecutionCodec.decode("{", QueueLimits.DEFAULTS).isEmpty());
        assertTrue(SavedExecutionCodec.decode("{\"version\":99}", QueueLimits.DEFAULTS)
                .isEmpty());
        assertTrue(
                SavedExecutionCodec.decode("{\"version\":0}", QueueLimits.DEFAULTS)
                        .isEmpty(),
                "an older execution document is rejected, not replayed");
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
