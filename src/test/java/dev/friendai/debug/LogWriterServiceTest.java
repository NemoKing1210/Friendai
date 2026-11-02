package dev.friendai.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.foundation.diagnostics.LogCategory;
import dev.friendai.foundation.diagnostics.LogEvents;
import dev.friendai.foundation.diagnostics.LogLevel;
import dev.friendai.foundation.diagnostics.LogRecord;
import dev.friendai.foundation.diagnostics.LogSettings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LogWriterServiceTest {
    private static final long NOW = Instant.parse("2023-11-14T22:13:20Z").toEpochMilli();
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String DAY = "2023-11-14";

    private static LogSettings settings(LogLevel level) {
        return new LogSettings(level, Set.of(), 10_000_000L, 100_000_000L, 30, 20, true);
    }

    private static LogWriterService writer(Path root, LogWriterScope scope, LogSettings settings, int queue) {
        UnaryOperator<String> redactor = text -> text.replace("SECRET", "***");
        return new LogWriterService(
                root, scope, "7f3c", "0.160.0", "26.3", uuid -> "Alex", settings, redactor, () -> NOW, queue);
    }

    private static LogRecord record(long seq, LogCategory category, UUID owner) {
        return new LogRecord(
                seq,
                NOW,
                LogLevel.INFO,
                category,
                null,
                LogEvents.SERVER_START,
                owner,
                null,
                "abc",
                0L,
                0L,
                0L,
                0L,
                "",
                0L,
                -1L,
                "message " + seq,
                Map.of(),
                null);
    }

    private static List<Path> jsonl(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream.filter(path -> path.toString().endsWith(".jsonl")).toList();
        }
    }

    @Test
    void writesAHeaderAndTheRecords(@TempDir Path root) throws IOException {
        try (LogWriterService writer = writer(root, LogWriterScope.SERVER, settings(LogLevel.INFO), 64)) {
            writer.offer(record(1, LogCategory.SERVER, null));
            writer.offer(record(2, LogCategory.SERVER, null));
            writer.flushNow().join();
        }
        List<Path> files = jsonl(root.resolve("server").resolve(DAY));
        assertEquals(1, files.size());
        List<String> lines = Files.readAllLines(files.get(0), StandardCharsets.UTF_8);
        assertEquals(3, lines.size());
        assertTrue(lines.get(0).contains("\"kind\":\"header\""));
        assertTrue(lines.get(1).contains("\"message\":\"message 1\"")
                || lines.get(1).contains("message 1"));
    }

    @Test
    void routesOwnerRecordsUnderTheirDirectory(@TempDir Path root) throws IOException {
        try (LogWriterService writer = writer(root, LogWriterScope.SERVER, settings(LogLevel.INFO), 64)) {
            writer.offer(record(1, LogCategory.BRAIN, OWNER));
            writer.flushNow().join();
        }
        List<Path> files =
                jsonl(root.resolve("owners").resolve(OWNER.toString()).resolve(DAY));
        assertEquals(1, files.size());
        assertTrue(files.get(0).toString().endsWith(".jsonl"));
        assertTrue(Files.readString(files.get(0), StandardCharsets.UTF_8).contains("\"Alex\""));
    }

    @Test
    void reportsDroppedRecordsWithoutBlocking(@TempDir Path root) throws IOException {
        int offers = 20_000;
        try (LogWriterService writer = writer(root, LogWriterScope.SERVER, settings(LogLevel.INFO), 2)) {
            for (int i = 0; i < offers; i++) {
                writer.offer(record(i + 1, LogCategory.SERVER, null));
            }
            writer.flushNow().join();
            LogWriterStats stats = writer.stats();
            assertEquals(offers, stats.accepted() + stats.dropped());
            assertTrue(stats.dropped() > 0);
        }
        boolean droppedRecorded = false;
        for (Path file : jsonl(root.resolve("server").resolve(DAY))) {
            if (Files.readString(file, StandardCharsets.UTF_8).contains(LogEvents.LOG_DROPPED)) {
                droppedRecorded = true;
                break;
            }
        }
        assertTrue(droppedRecorded);
    }

    @Test
    void rotatesAtTheFileBoundary(@TempDir Path root) throws IOException {
        LogSettings small = new LogSettings(LogLevel.INFO, Set.of(), 512L, 10_000_000L, 30, 20, true);
        try (LogWriterService writer = writer(root, LogWriterScope.SERVER, small, 64)) {
            for (int i = 0; i < 40; i++) {
                writer.offer(record(i + 1, LogCategory.SERVER, null));
            }
            writer.flushNow().join();
        }
        List<Path> files = jsonl(root.resolve("server").resolve(DAY));
        assertTrue(files.size() >= 2);
        assertTrue(files.stream().anyMatch(path -> path.getFileName().toString().contains("-2")));
        for (Path file : files) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            assertTrue(lines.get(0).contains("\"kind\":\"header\""), "a part must start with its header");
            long headers = lines.stream()
                    .filter(line -> line.contains("\"kind\":\"header\""))
                    .count();
            assertEquals(1, headers, "only the first line of " + file + " may be a header");
            assertTrue(Files.size(file) <= 1024L, "a part must stay near the cap: " + file);
        }
    }

    @Test
    void prunesOldFilesOnOpen(@TempDir Path root) throws IOException {
        Path day = root.resolve("server").resolve("2023-11-01");
        Files.createDirectories(day);
        Path old = day.resolve("boot-old.jsonl");
        Files.writeString(old, "{}\n", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(old, FileTime.fromMillis(NOW - 2 * 86_400_000L));
        LogSettings oneDay = new LogSettings(LogLevel.INFO, Set.of(), 10_000_000L, 100_000_000L, 1, 20, true);
        try (LogWriterService writer = writer(root, LogWriterScope.SERVER, oneDay, 64)) {
            writer.offer(record(1, LogCategory.SERVER, null));
            writer.flushNow().join();
        }
        assertFalse(Files.exists(old));
    }

    @Test
    void anUnwritableRootReportsAnErrorInsteadOfThrowing(@TempDir Path dir) throws IOException {
        Path root = dir.resolve("not-a-directory");
        Files.writeString(root, "x", StandardCharsets.UTF_8);
        try (LogWriterService writer = writer(root, LogWriterScope.SERVER, settings(LogLevel.INFO), 8)) {
            writer.offer(record(1, LogCategory.SERVER, null));
            writer.flushNow().join();
            assertTrue(writer.stats().errors() > 0);
        }
    }

    @Test
    void closingWritesTheDigest(@TempDir Path root) throws IOException {
        try (LogWriterService writer = writer(root, LogWriterScope.SERVER, settings(LogLevel.INFO), 64)) {
            writer.offer(record(1, LogCategory.SERVER, null));
            writer.offer(record(2, LogCategory.SERVER, null));
            writer.flushNow().join();
        }
        List<Path> files = jsonl(root.resolve("server").resolve(DAY));
        assertEquals(1, files.size());
        Path digest = files.get(0)
                .resolveSibling(files.get(0).getFileName().toString().replace(".jsonl", ".md"));
        assertTrue(Files.exists(digest));
        assertTrue(Files.readString(digest, StandardCharsets.UTF_8).contains("# Session"));
    }

    @Test
    void aClientWriterTouchesOnlyItsOwnSubtree(@TempDir Path root) throws IOException {
        Path serverDay = root.resolve("server").resolve("2023-11-01");
        Files.createDirectories(serverDay);
        Path sentinel = serverDay.resolve("boot-old.jsonl");
        Files.writeString(sentinel, "{}\n", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(sentinel, FileTime.fromMillis(NOW - 5 * 86_400_000L));
        LogSettings oneDay = new LogSettings(LogLevel.INFO, Set.of(LogCategory.CLIENT), 10_000_000L, 1L, 1, 20, true);
        try (LogWriterService writer = writer(root, LogWriterScope.CLIENT, oneDay, 64)) {
            LogRecord client = new LogRecord(
                    1L,
                    NOW,
                    LogLevel.INFO,
                    LogCategory.CLIENT,
                    null,
                    LogEvents.CLIENT_ERROR,
                    OWNER,
                    null,
                    "",
                    0L,
                    0L,
                    0L,
                    0L,
                    "",
                    0L,
                    -1L,
                    "client",
                    Map.of(),
                    null);
            writer.offer(client);
            writer.flushNow().join();
        }
        assertTrue(Files.exists(sentinel), "the server subtree belongs to the other writer");
        assertEquals(1, jsonl(root.resolve("client").resolve(DAY)).size());
    }
}
