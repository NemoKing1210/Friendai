package dev.friendai.foundation.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class LogPipelineTest {
    private static final class Recall implements LogSink {
        final List<LogRecord> records = new ArrayList<>();

        @Override
        public void accept(LogRecord record) {
            records.add(record);
        }
    }

    private static LogSettings settings(LogLevel level, Set<LogCategory> categories) {
        return new LogSettings(level, categories, 1_000_000L, 10_000_000L, 7, 20, true);
    }

    private static LogRecord draft(String message) {
        return new LogRecord(
                0L,
                0L,
                LogLevel.INFO,
                LogCategory.BRAIN,
                LogKind.USER,
                LogEvents.TURN_START,
                null,
                null,
                "",
                1L,
                0L,
                0L,
                0L,
                "",
                0L,
                -1L,
                message,
                Map.of("k", "v"),
                null);
    }

    @Test
    void emitAssignsIncreasingSequencesAndATime() {
        Recall ring = new Recall();
        LogPipeline pipeline = new LogPipeline(ring, () -> settings(LogLevel.INFO, Set.of()), text -> text);
        assertEquals(1L, pipeline.emit(draft("one")));
        assertEquals(2L, pipeline.emit(draft("two")));
        assertEquals(3L, pipeline.emit(draft("three")));
        assertEquals(
                List.of(1L, 2L, 3L), ring.records.stream().map(LogRecord::seq).toList());
        assertTrue(ring.records.get(0).timeMillis() > 0L);
    }

    @Test
    void offStillFeedsTheRingButNoFile() {
        Recall ring = new Recall();
        Recall file = new Recall();
        LogPipeline pipeline = new LogPipeline(ring, () -> settings(null, Set.of()), text -> text, file);
        pipeline.emit(draft("kept in memory"));
        assertEquals(1, ring.records.size());
        assertEquals(0, file.records.size());
    }

    @Test
    void levelAndCategoryFilterOnlyTheFileSink() {
        Recall ring = new Recall();
        Recall file = new Recall();
        LogPipeline pipeline =
                new LogPipeline(ring, () -> settings(LogLevel.WARN, Set.of(LogCategory.TOOL)), text -> text, file);
        pipeline.emit(draft("info"));
        pipeline.emit(new LogRecord(
                0L,
                0L,
                LogLevel.WARN,
                LogCategory.TOOL,
                LogKind.TOOL_CALL,
                LogEvents.TOOL_CALL,
                null,
                null,
                "",
                0L,
                0L,
                0L,
                0L,
                "",
                0L,
                -1L,
                "warn",
                Map.of(),
                null));
        pipeline.emit(new LogRecord(
                0L,
                0L,
                LogLevel.WARN,
                LogCategory.TASK,
                LogKind.TASK,
                LogEvents.TASK_START,
                null,
                null,
                "",
                0L,
                0L,
                0L,
                0L,
                "",
                0L,
                -1L,
                "wrong category",
                Map.of(),
                null));
        assertEquals(3, ring.records.size());
        assertEquals(1, file.records.size());
        assertEquals(LogCategory.TOOL, file.records.get(0).category());
    }

    @Test
    void updateAppliesToTheNextRecord() {
        Recall file = new Recall();
        LogSettings[] current = {settings(null, Set.of())};
        LogPipeline pipeline = new LogPipeline(new Recall(), () -> current[0], text -> text, file);
        pipeline.emit(draft("dropped"));
        current[0] = settings(LogLevel.INFO, Set.of());
        pipeline.update(current[0]);
        pipeline.emit(draft("kept"));
        assertEquals(1, file.records.size());
        assertEquals("kept", file.records.get(0).message());
    }

    @Test
    void redactsTheMessageDataAndErrorBeforeAnySink() {
        Recall ring = new Recall();
        UnaryOperator<String> redactor = text -> text.replace("SECRET", "***");
        LogPipeline pipeline = new LogPipeline(ring, () -> settings(LogLevel.INFO, Set.of()), redactor);
        LogRecord record = new LogRecord(
                0L,
                0L,
                LogLevel.ERROR,
                LogCategory.SERVER,
                null,
                LogEvents.SERVER_ERROR,
                null,
                null,
                "",
                0L,
                0L,
                0L,
                0L,
                "",
                0L,
                -1L,
                "key SECRET",
                Map.of("k", "SECRET"),
                new LogError("E", "SECRET", "SECRET", 1));
        pipeline.emit(record);
        LogRecord stored = ring.records.get(0);
        assertEquals("key ***", stored.message());
        assertEquals("***", stored.data().get("k"));
        assertEquals("***", stored.error().message());
        assertEquals("***", stored.error().stack());
    }

    @Test
    void aRecordWithoutAnOwnerReachesOnlyFileSinks() {
        Recall file = new Recall();
        LogPipeline pipeline =
                new LogPipeline(new Recall(), () -> settings(LogLevel.INFO, Set.of()), text -> text, file);
        pipeline.emit(draft("server-wide"));
        assertEquals(1, file.records.size());
        assertNull(file.records.get(0).owner());
    }
}
