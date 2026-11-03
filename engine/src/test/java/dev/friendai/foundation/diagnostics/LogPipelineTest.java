package dev.friendai.foundation.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    @Test
    void boundsDirectlyProducedRecordsBeforeTheRingAndTheFile() {
        Recall ring = new Recall();
        Recall file = new Recall();
        LogPipeline pipeline = new LogPipeline(ring, () -> settings(LogLevel.INFO, Set.of()), text -> text, file);
        Map<String, String> data = new LinkedHashMap<>();
        for (int i = 0; i < 40; i++) {
            data.put("key" + i + "-" + "k".repeat(40), "v".repeat(500));
        }
        pipeline.emit(new LogRecord(
                0L,
                0L,
                LogLevel.ERROR,
                LogCategory.TOOL,
                LogKind.TOOL_CALL,
                "e".repeat(200),
                null,
                null,
                "s".repeat(64),
                0L,
                0L,
                0L,
                0L,
                "c".repeat(200),
                0L,
                -1L,
                "m".repeat(2000),
                data,
                new LogError("t".repeat(200), "e".repeat(2000), "s".repeat(8192), 500)));

        assertEquals(1, ring.records.size());
        assertEquals(1, file.records.size());
        for (LogRecord stored : List.of(ring.records.get(0), file.records.get(0))) {
            assertEquals(LogLimits.MAX_MESSAGE_CHARS, stored.message().length());
            assertEquals(16, stored.session().length());
            assertEquals(64, stored.toolCall().length());
            assertEquals(64, stored.event().length());
            assertTrue(
                    stored.data().size() <= LogLimits.MAX_DATA_KEYS,
                    "data keys: " + stored.data().size());
            assertNotNull(stored.data().get("truncated"));
            for (Map.Entry<String, String> entry : stored.data().entrySet()) {
                assertTrue(entry.getKey().length() <= LogLimits.MAX_KEY_CHARS);
                assertTrue(entry.getValue().length() <= LogLimits.MAX_VALUE_CHARS);
            }
            assertEquals(80, stored.error().type().length());
            assertEquals(LogLimits.MAX_MESSAGE_CHARS, stored.error().message().length());
            assertEquals(LogLimits.MAX_STACK_CHARS, stored.error().stack().length());
            assertEquals(LogLimits.MAX_STACK_FRAMES, stored.error().frames());
            assertTrue(LogJson.decode(LogJson.encode(stored)).isPresent());
        }
    }

    @Test
    void keepsIdentityKeysWhenBoundingCrowdedData() {
        Recall ring = new Recall();
        LogPipeline pipeline = new LogPipeline(ring, () -> settings(LogLevel.INFO, Set.of()), text -> text);
        Map<String, String> data = new LinkedHashMap<>();
        for (int i = 0; i < 24; i++) {
            data.put("fact" + i, "v" + i);
        }
        data.put("execution", "7");
        data.put("span", "2");
        data.put("trace", "t-0123456789");
        data.put("parentSpan", "1");
        pipeline.emit(new LogRecord(
                0L,
                0L,
                LogLevel.INFO,
                LogCategory.TASK,
                null,
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
                "started",
                data,
                null));

        LogRecord stored = ring.records.get(0);
        assertEquals("7", stored.data().get("execution"));
        assertEquals("2", stored.data().get("span"));
        assertEquals("t-0123456789", stored.data().get("trace"));
        assertEquals("1", stored.data().get("parentSpan"));
        assertNotNull(stored.data().get("truncated"));
    }

    @Test
    void redactsSecretsBeforeBoundingTheRingAndFileCopies() {
        String secret = "sk-live-0123456789abcdef";
        UnaryOperator<String> redactor = text -> text.replace(secret, "***");
        Recall ring = new Recall();
        Recall file = new Recall();
        LogPipeline pipeline = new LogPipeline(ring, () -> settings(LogLevel.INFO, Set.of()), redactor, file);
        String message = "x".repeat(LogLimits.MAX_MESSAGE_CHARS - secret.length() - 1) + secret + "y".repeat(200);
        Map<String, String> data = new LinkedHashMap<>();
        data.put("detail", secret);
        data.put("bulk", secret.repeat(30));
        pipeline.emit(new LogRecord(
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
                message,
                data,
                new LogError("E", secret, "at " + secret, 1)));

        for (LogRecord stored : List.of(ring.records.get(0), file.records.get(0))) {
            assertFalse(stored.message().contains(secret), "message kept a secret");
            assertEquals("***", stored.data().get("detail"));
            assertFalse(stored.data().get("bulk").contains(secret));
            assertFalse(stored.error().message().contains(secret));
            assertFalse(stored.error().stack().contains(secret));
            assertTrue(stored.message().length() <= LogLimits.MAX_MESSAGE_CHARS);
        }
    }
}
