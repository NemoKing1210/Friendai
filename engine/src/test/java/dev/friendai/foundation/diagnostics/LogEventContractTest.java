package dev.friendai.foundation.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LogEventContractTest {
    private static final String CLASSIC = "/diagnostics/schema1-classic.jsonl";
    private static final String METADATA = "/diagnostics/schema1-metadata.jsonl";

    @Test
    void decodesClassicSchemaOneRecordsWithoutDiagnosticMetadata() throws IOException {
        List<String> lines = lines(CLASSIC);
        assertEquals(4, lines.size());
        assertTrue(LogJson.decodeHeader(lines.get(0)).isPresent(), "classic header must decode");

        List<LogRecord> records = records(lines);
        assertEquals(3, records.size());
        assertEquals(LogEvents.TURN_START, records.get(0).event());
        assertEquals(-1L, records.get(1).durationMs());
        assertTrue(records.get(1).data().isEmpty());
        assertEquals("IllegalStateException", records.get(2).error().type());
        assertEquals(2L, records.get(2).cause());
    }

    @Test
    void decodesDiagnosticMetadataAndToleratesUnknownEventsAndFacts() throws IOException {
        List<String> lines = lines(METADATA);
        assertTrue(LogJson.decodeHeader(lines.get(0)).isPresent());

        List<LogRecord> records = records(lines);
        assertEquals(3, records.size());
        assertEquals(
                Map.of("trace", "t-0001", "execution", "7", "span", "2", "parentSpan", "1"),
                Map.of(
                        "trace", records.get(0).data().get("trace"),
                        "execution", records.get(0).data().get("execution"),
                        "span", records.get(0).data().get("span"),
                        "parentSpan", records.get(0).data().get("parentSpan")));
        assertEquals("search_selected", records.get(0).data().get("reason"));
        assertEquals(2L, records.get(0).turn());
        assertEquals(5L, records.get(0).plan());
        assertEquals(6L, records.get(0).job());
        assertEquals(LogEvents.SPAN_START, records.get(1).event());
        assertEquals("future.event", records.get(2).event(), "an unknown event stays readable");
        assertEquals("opaque value", records.get(2).data().get("unknownFact"));
    }

    @Test
    void theBuilderPutsIdentityKeysFirstAndCannotBeForged() {
        LogContext context = new LogContext("t-0001", 0L, 0L, 0L, 0L, 7L, 2L, 1L, "", 0L);
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("execution", "999");
        facts.put("trace", "forged");
        facts.put("reason", "search_selected");

        Map<String, String> data = LogEventData.build(context, facts);

        assertEquals("t-0001", data.get("trace"));
        assertEquals("7", data.get("execution"));
        assertEquals("2", data.get("span"));
        assertEquals("1", data.get("parentSpan"));
        assertEquals("search_selected", data.get("reason"));
        assertEquals(List.of("trace", "execution", "span", "parentSpan", "reason"), List.copyOf(data.keySet()));
    }

    @Test
    void theBuilderReservesACapacitySlotAndReportsDroppedFacts() {
        Map<String, String> facts = new LinkedHashMap<>();
        for (int i = 0; i < 30; i++) {
            facts.put("fact" + i, "value" + i);
        }

        Map<String, String> data = LogEventData.build(LogContext.NONE, facts);

        assertEquals(LogLimits.MAX_DATA_KEYS, data.size());
        assertEquals("15", data.get(LogEventData.TRUNCATED_KEY));
        assertTrue(data.containsKey("fact0"));
        assertFalse(data.containsKey("fact15"));
    }

    @Test
    void boundsApplyToAProducerThatBypassedTheBuilder() {
        Map<String, String> data = new LinkedHashMap<>();
        for (int i = 0; i < 30; i++) {
            data.put("fact" + i, "v".repeat(400));
        }
        LogRecord direct = new LogRecord(
                0L,
                0L,
                LogLevel.INFO,
                LogCategory.TASK,
                null,
                "e".repeat(120),
                null,
                null,
                "s".repeat(40),
                0L,
                0L,
                0L,
                0L,
                "c".repeat(120),
                0L,
                -1L,
                "m".repeat(900),
                data,
                new LogError("t".repeat(120), "e".repeat(900), "s".repeat(4000), 100));

        LogRecord bounded = LogRecordBounds.apply(direct);

        assertEquals(LogLimits.MAX_MESSAGE_CHARS, bounded.message().length());
        assertEquals(LogLimits.MAX_EVENT_CHARS, bounded.event().length());
        assertEquals(LogLimits.MAX_SESSION_CHARS, bounded.session().length());
        assertEquals(LogLimits.MAX_TOOL_CALL_CHARS, bounded.toolCall().length());
        assertEquals(LogLimits.MAX_DATA_KEYS, bounded.data().size());
        assertEquals(LogLimits.MAX_ERROR_TYPE_CHARS, bounded.error().type().length());
        assertEquals(LogLimits.MAX_STACK_CHARS, bounded.error().stack().length());
        assertEquals(LogLimits.MAX_STACK_FRAMES, bounded.error().frames());
        assertTrue(bounded.data().containsKey(LogEventData.TRUNCATED_KEY));
        bounded.data().forEach((key, value) -> {
            assertTrue(key.length() <= LogLimits.MAX_KEY_CHARS);
            assertTrue(value.length() <= LogLimits.MAX_VALUE_CHARS);
        });
        assertSame(bounded, LogRecordBounds.apply(bounded), "a bounded record is not copied again");
    }

    @Test
    void utf8EscapedAndMultiLineTextStaysValidBoundedJson() {
        LogRecord record = new LogRecord(
                1L,
                1L,
                LogLevel.INFO,
                LogCategory.SERVER,
                null,
                LogEvents.SERVER_WARN,
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
                "漢".repeat(300) + "\n\"quoted\"\\",
                Map.of("note", "line one\nline two\t" + "漢".repeat(200)),
                null);

        String encoded = LogJson.encode(LogRecordBounds.apply(record));

        assertEquals(1, encoded.lines().count(), "one JSONL line is expected");
        assertTrue(encoded.getBytes(StandardCharsets.UTF_8).length <= LogLimits.MAX_LINE_BYTES);
        LogRecord decoded = LogJson.decode(encoded).orElseThrow();
        assertTrue(decoded.message().length() <= LogLimits.MAX_MESSAGE_CHARS);
        assertTrue(decoded.data().get("note").length() <= LogLimits.MAX_VALUE_CHARS);
        assertFalse(encoded.contains("\n\""));
    }

    @Test
    void everyDeclaredEventTokenIsRegistered() throws IllegalAccessException {
        for (Field field : LogEvents.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (!Modifier.isStatic(modifiers) || !Modifier.isFinal(modifiers) || field.getType() != String.class) {
                continue;
            }
            String token = (String) field.get(null);
            assertTrue(LogEvents.all().contains(token), field.getName() + " -> " + token + " is missing from all()");
        }
        assertEquals(LogEvents.all().size(), LogEvents.all().stream().distinct().count());
    }

    private static List<String> lines(String resource) throws IOException {
        try (InputStream in = LogEventContractTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "missing fixture " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        }
    }

    private static List<LogRecord> records(List<String> lines) {
        List<LogRecord> records = new ArrayList<>();
        for (String line : lines) {
            if (line.isBlank() || LogJson.decodeHeader(line).isPresent()) continue;
            records.add(LogJson.decode(line).orElseThrow(() -> new AssertionError("undecodable line: " + line)));
        }
        return records;
    }
}
