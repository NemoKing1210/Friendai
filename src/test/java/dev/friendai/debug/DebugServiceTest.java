package dev.friendai.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.execution.log.TaskLogEvents;
import dev.friendai.foundation.diagnostics.LogCategory;
import dev.friendai.foundation.diagnostics.LogEvents;
import dev.friendai.foundation.diagnostics.LogKind;
import dev.friendai.foundation.diagnostics.LogLevel;
import dev.friendai.foundation.diagnostics.LogRecord;
import dev.friendai.foundation.diagnostics.LogSettings;
import dev.friendai.foundation.diagnostics.LogSink;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DebugServiceTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final class Recall implements LogSink {
        final List<LogRecord> records = new ArrayList<>();

        @Override
        public void accept(LogRecord record) {
            records.add(record);
        }
    }

    private static LogRecord record(UUID owner, LogKind kind, LogLevel level, String message) {
        return new LogRecord(
                0L,
                1L,
                level,
                LogCategory.BRAIN,
                kind,
                LogEvents.TURN_START,
                owner,
                null,
                "s",
                1L,
                0L,
                0L,
                0L,
                "",
                0L,
                -1L,
                message,
                Map.of(),
                null);
    }

    private static LogSettings settings(LogLevel level) {
        return new LogSettings(level, Set.of(), 1_000_000L, 10_000_000L, 7, 20, true);
    }

    @Test
    void aRecordWithAnOwnerAndAKindLandsInTheRingAndTheFileSink() {
        Recall file = new Recall();
        DebugService service = new DebugService(text -> text, () -> 1L, () -> settings(LogLevel.INFO), file);
        service.pipeline().emit(record(OWNER, LogKind.USER, LogLevel.INFO, "hello"));
        assertEquals(1, file.records.size());
        assertEquals(1, service.logOf(OWNER).size());
        assertEquals("hello", service.logOf(OWNER).after(0, 10).get(0).text());
    }

    @Test
    void aRecordWithoutAnOwnerOrKindReachesOnlyTheFileSink() {
        Recall file = new Recall();
        DebugService service = new DebugService(text -> text, () -> 1L, () -> settings(LogLevel.INFO), file);
        service.pipeline().emit(record(null, null, LogLevel.INFO, "server"));
        service.pipeline().emit(record(OWNER, null, LogLevel.INFO, "no role"));
        assertEquals(2, file.records.size());
        assertEquals(0, service.logOf(OWNER).size());
    }

    @Test
    void updateChangesFilteringForTheNextRecord() {
        Recall file = new Recall();
        LogSettings[] current = {settings(null)};
        DebugService service = new DebugService(text -> text, () -> 1L, () -> current[0], file);
        service.pipeline().emit(record(OWNER, LogKind.USER, LogLevel.INFO, "off"));
        current[0] = settings(LogLevel.INFO);
        service.update(current[0]);
        service.pipeline().emit(record(OWNER, LogKind.USER, LogLevel.INFO, "on"));
        assertEquals(1, file.records.size());
        assertEquals("on", file.records.get(0).message());
        assertTrue(service.logOf(OWNER).size() >= 2);
    }

    @Test
    void eachProgressEmissionReachesTheRingAndTheFileOnceAndRedacted() {
        Recall file = new Recall();
        DebugService service = new DebugService(
                text -> text.replace("sk-live-123", "[redacted]"), () -> 1L, () -> settings(LogLevel.DEBUG), file);
        String text = "mine 3 × minecraft:oak_log (key sk-live-123)";
        service.pipeline().emit(TaskLogEvents.progress(OWNER, null, "s", 0L, text));
        service.pipeline().emit(TaskLogEvents.progress(OWNER, null, "s", 0L, text));
        assertEquals(2, file.records.size(), "one emitted progress record is one file record");
        List<String> rows = service.logOf(OWNER).after(0, 10).stream()
                .map(entry -> entry.text())
                .toList();
        assertEquals(
                List.of("mine 3 × minecraft:oak_log (key [redacted])", "mine 3 × minecraft:oak_log (key [redacted])"),
                rows,
                "one emitted progress record is one ring row, and identical texts stay two rows");
        for (LogRecord record : file.records) {
            assertFalse(record.message().contains("sk-live-123"), "the file record must be redacted");
        }
    }

    @Test
    void aProgressRecordStillFillsTheRingWhenFileLoggingIsOff() {
        Recall file = new Recall();
        DebugService service = new DebugService(text -> text, () -> 1L, () -> settings(null), file);
        service.pipeline().emit(TaskLogEvents.progress(OWNER, null, "s", 0L, "mine"));
        assertEquals(1, service.logOf(OWNER).after(0, 10).size(), "the ring shows the line with logging off");
        assertEquals(0, file.records.size(), "no file record may be written with logging off");
    }

    @Test
    void theLegacyConstructorKeepsTheRingWorking() {
        DebugService service = new DebugService(text -> text, () -> 1L);
        service.logOf(OWNER).add(LogKind.USER, "legacy");
        assertFalse(service.logOf(OWNER).after(0, 10).isEmpty());
    }
}
