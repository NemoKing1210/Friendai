package dev.friendai.foundation.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LogJsonTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID COMPANION = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static LogRecord full() {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("provider", "anthropic");
        data.put("attempt", "2");
        return new LogRecord(
                7L,
                1_700_000_000_000L,
                LogLevel.WARN,
                LogCategory.TOOL,
                LogKind.TOOL_CALL,
                LogEvents.TOOL_CALL,
                OWNER,
                COMPANION,
                "abc",
                4L,
                0L,
                3L,
                0L,
                "call-1",
                5L,
                -1L,
                "mining started",
                data,
                new LogError("IllegalStateException", "boom", "at a.b(C.java:1)", 1));
    }

    private static LogRecord minimal() {
        return new LogRecord(
                1L,
                1L,
                LogLevel.INFO,
                LogCategory.SERVER,
                null,
                LogEvents.SERVER_START,
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
                Map.of(),
                null);
    }

    @Test
    void roundTripsEveryField() {
        LogRecord decoded = LogJson.decode(LogJson.encode(full())).orElseThrow();
        assertEquals(full(), decoded);
    }

    @Test
    void roundTripsANullKind() {
        LogRecord decoded = LogJson.decode(LogJson.encode(minimal())).orElseThrow();
        assertNull(decoded.kind());
        assertEquals(minimal(), decoded);
    }

    @Test
    void truncatesLongMessageWithEllipsis() {
        LogRecord longMessage = new LogRecord(
                1L,
                1L,
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
                "a".repeat(10_000),
                Map.of(),
                null);
        String line = LogJson.encode(longMessage);
        LogRecord decoded = LogJson.decode(line).orElseThrow();
        assertEquals(LogLimits.MAX_MESSAGE_CHARS, decoded.message().length());
        assertTrue(decoded.message().endsWith("…"));
        assertTrue(line.contains("\"truncated\":true"));
    }

    @Test
    void keepsAtMostSixteenDataKeys() {
        Map<String, String> data = new LinkedHashMap<>();
        for (int i = 0; i < 20; i++) {
            data.put("k" + i, "v" + i);
        }
        LogRecord record = new LogRecord(
                1L,
                1L,
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
                "m",
                data,
                null);
        LogRecord decoded = LogJson.decode(LogJson.encode(record)).orElseThrow();
        assertEquals(LogLimits.MAX_DATA_KEYS, decoded.data().size());
        assertTrue(LogJson.encode(record).contains("\"truncated\":true"));
    }

    @Test
    void truncatesLongStackAndFrames() {
        String stack = "x".repeat(5000);
        LogRecord record = new LogRecord(
                1L,
                1L,
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
                "m",
                Map.of(),
                new LogError("E", "m", stack, 37));
        LogRecord decoded = LogJson.decode(LogJson.encode(record)).orElseThrow();
        assertTrue(decoded.error().stack().length() <= LogLimits.MAX_STACK_CHARS);
        assertEquals(LogLimits.MAX_STACK_FRAMES, decoded.error().frames());
    }

    @Test
    void neverEmitsNewlines() {
        LogRecord record = new LogRecord(
                1L,
                1L,
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
                "line one\nline two\rline three",
                Map.of("k", "a\nb"),
                null);
        String line = LogJson.encode(record);
        assertFalse(line.contains("\n"));
        assertFalse(line.contains("\r"));
    }

    @Test
    void keepsAUnicodeHeavyRecordWithinTheByteCap() {
        Map<String, String> data = new LinkedHashMap<>();
        for (int i = 0; i < LogLimits.MAX_DATA_KEYS; i++) {
            data.put("k" + i, "漢".repeat(LogLimits.MAX_VALUE_CHARS));
        }
        LogRecord record = new LogRecord(
                1L,
                1L,
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
                "漢".repeat(LogLimits.MAX_MESSAGE_CHARS),
                data,
                null);
        String line = LogJson.encode(record);
        assertTrue(line.getBytes(StandardCharsets.UTF_8).length <= LogLimits.MAX_LINE_BYTES);
        assertTrue(LogJson.decode(line).isPresent());
    }

    @Test
    void rejectsInvalidInput() {
        assertTrue(LogJson.decode("not json").isEmpty());
        assertTrue(LogJson.decode("{}").isEmpty());
        assertTrue(LogJson.decode("{\"schema\":1,\"seq\":1,\"timeMillis\":1,\"level\":\"loud\",\"category\":\"brain\"}")
                .isEmpty());
        assertTrue(LogJson.decode("{\"schema\":1,\"seq\":1,\"timeMillis\":1,\"level\":\"info\",\"category\":\"nope\"}")
                .isEmpty());
        assertTrue(LogJson.decode("{\"schema\":2,\"seq\":1,\"timeMillis\":1,\"level\":\"info\",\"category\":\"brain\"}")
                .isEmpty());
    }

    @Test
    void ignoresUnknownFields() {
        String line = "{\"schema\":1,\"seq\":1,\"timeMillis\":1,\"level\":\"info\",\"category\":\"brain\","
                + "\"event\":\"turn.start\",\"future\":true}";
        LogRecord decoded = LogJson.decode(line).orElseThrow();
        assertEquals(1L, decoded.seq());
        assertEquals(LogEvents.TURN_START, decoded.event());
    }

    @Test
    void roundTripsNonAscii() {
        LogRecord record = new LogRecord(
                1L,
                1L,
                LogLevel.INFO,
                LogCategory.BRAIN,
                LogKind.ASSISTANT,
                LogEvents.ASSISTANT_TEXT,
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
                "Привет, 世界",
                Map.of("k", "ё"),
                null);
        assertEquals(record, LogJson.decode(LogJson.encode(record)).orElseThrow());
    }

    @Test
    void roundTripsTheFileHeader() {
        LogFileHeader header = new LogFileHeader(
                1, "7f3c", "New World", "0.160.0", "26.3", OWNER, "Alex", "normal", List.of("brain", "tool"), 1000L);
        assertEquals(Optional.of(header), LogJson.decodeHeader(LogJson.encodeHeader(header)));
    }

    @Test
    void aHeaderNamesItsWorld() {
        LogFileHeader header =
                new LogFileHeader(1, "7f3c", "New World", "0.160.0", "26.3", OWNER, "Alex", "normal", List.of(), 1000L);
        assertTrue(LogJson.encodeHeader(header).contains("\"world\":\"New World\""), LogJson.encodeHeader(header));
    }

    @Test
    void aFileFromBeforeTheChangeStillDecodes() {
        String line = "{\"schema\":1,\"kind\":\"header\",\"boot\":\"7f3c\",\"mod\":\"0.160.0\",\"minecraft\":\"26.3\","
                + "\"name\":\"Alex\",\"level\":\"normal\",\"categories\":[],\"started\":1}";
        assertEquals("", LogJson.decodeHeader(line).orElseThrow().world());
    }

    @Test
    void rejectsAFutureHeaderSchema() {
        String line = "{\"schema\":2,\"kind\":\"header\",\"boot\":\"7f3c\"}";
        assertTrue(LogJson.decodeHeader(line).isEmpty());
    }

    @Test
    void recordsCarryASchema() {
        assertTrue(LogJson.encode(minimal()).contains("\"schema\":1"));
        assertTrue(LogJson.encodeHeader(
                        new LogFileHeader(1, "7f3c", "", "0.160.0", "26.3", null, "", "normal", List.of(), 1L))
                .contains("\"schema\":1"));
    }

    @Test
    void aMaximalRecordDecodesAfterHittingTheCap() {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            values.add("漢".repeat(200));
        }
        Map<String, String> data = new LinkedHashMap<>();
        for (int i = 0; i < values.size(); i++) {
            data.put("k" + i, values.get(i));
        }
        LogRecord record = new LogRecord(
                1L,
                1L,
                LogLevel.WARN,
                LogCategory.TOOL,
                LogKind.TOOL_CALL,
                LogEvents.TOOL_CALL,
                OWNER,
                COMPANION,
                "abc",
                1L,
                0L,
                0L,
                0L,
                "c",
                0L,
                5L,
                "m",
                data,
                null);
        assertTrue(LogJson.decode(LogJson.encode(record)).isPresent());
    }
}
