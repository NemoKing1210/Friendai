package dev.friendai.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.foundation.diagnostics.LogCategory;
import dev.friendai.foundation.diagnostics.LogEvents;
import dev.friendai.foundation.diagnostics.LogFlushResult;
import dev.friendai.foundation.diagnostics.LogHealth;
import dev.friendai.foundation.diagnostics.LogJson;
import dev.friendai.foundation.diagnostics.LogLevel;
import dev.friendai.foundation.diagnostics.LogRecord;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiagnosticServiceTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    private static DiagnosticService service(Path gameDir) {
        return new DiagnosticService(gameDir.resolve("logs").resolve("friendai"));
    }

    @Test
    void bundlesLiveUnderTheLogRootItWasGiven(@TempDir Path worldPath) {
        Path logRoot = worldPath.resolve("friendai").resolve("logs");
        assertEquals(logRoot.resolve("diagnostics"), new DiagnosticService(logRoot).root());
    }

    private static DiagnosticSnapshot snapshot(Path gameDir) {
        return new DiagnosticSnapshot(
                "Alex",
                OWNER,
                gameDir.resolve("logs").resolve("friendai").resolve("owners").resolve(OWNER.toString()),
                "boot-1",
                "New World",
                "0.200.0",
                "26.3",
                "normal",
                List.of("task"),
                Map.of("profile", "main"),
                new LogHealth(3L, 2L, 2L, 0L, 0L, 0L, 0, 1, 0L, LogHealth.State.OPEN));
    }

    private static void writeLog(Path gameDir) throws IOException {
        Path dir = gameDir.resolve("logs")
                .resolve("friendai")
                .resolve("owners")
                .resolve(OWNER.toString())
                .resolve("2026-11-04");
        Files.createDirectories(dir);
        LogRecord record = new LogRecord(
                1L,
                1L,
                LogLevel.INFO,
                LogCategory.TASK,
                null,
                LogEvents.TASK_START,
                OWNER,
                null,
                "s1",
                1L,
                0L,
                0L,
                0L,
                "obtain_item",
                0L,
                -1L,
                "obtain: started",
                Map.of("trace", "t-1", "execution", "7"),
                null);
        LogRecord finish = new LogRecord(
                2L,
                2L,
                LogLevel.INFO,
                LogCategory.TASK,
                null,
                LogEvents.TASK_FINISH,
                OWNER,
                null,
                "s1",
                1L,
                0L,
                0L,
                0L,
                "obtain_item",
                1L,
                -1L,
                "obtain_item: success",
                Map.of("trace", "t-1", "execution", "7", "activeTicks", "5"),
                null);
        Files.writeString(
                dir.resolve("session-1.jsonl"),
                LogJson.encode(record) + "\n" + LogJson.encode(finish) + "\n",
                StandardCharsets.UTF_8);
    }

    private static String read(Path bundle, String name) throws IOException {
        return Files.readString(bundle.resolve(name), StandardCharsets.UTF_8);
    }

    @Test
    void writesManifestEventsAndIncidentForAHealthyFlush(@TempDir Path gameDir) throws Exception {
        writeLog(gameDir);
        try (DiagnosticService service = service(gameDir)) {
            CompletableFuture<LogFlushResult> flush = CompletableFuture.completedFuture(
                    new LogFlushResult(LogFlushResult.Status.FLUSHED, gameDir.resolve("x.jsonl"), 3L, 3L, 2L, 1L, ""));
            Path bundle = service.writeIncident(Map.of("summary.txt", "facts"), snapshot(gameDir), flush, text -> text)
                    .get(10, TimeUnit.SECONDS);

            assertTrue(Files.exists(bundle.resolve("summary.txt")));
            assertTrue(Files.exists(bundle.resolve("events.jsonl")));
            String manifest = read(bundle, "manifest.json");
            assertTrue(manifest.contains("\"flush\":\"FLUSHED\""), manifest);
            assertTrue(manifest.contains("\"partial\":\"\""));
            assertTrue(manifest.contains("\"profile\":\"main\""));
            assertEquals(
                    2,
                    read(bundle, "events.jsonl")
                            .lines()
                            .filter(line -> !line.isBlank())
                            .count());
            String incident = read(bundle, "incident.md");
            assertTrue(incident.contains("Records: 2"));
            assertTrue(incident.contains(" in New World"), incident);
            assertTrue(incident.contains("exec 7"));
            assertTrue(incident.contains("; complete"), "a clean scan with a clean writer is complete");
        }
    }

    @Test
    void aBusyFlushMarksTheBundlePartialInsteadOfClaimingCompletion(@TempDir Path gameDir) throws Exception {
        writeLog(gameDir);
        try (DiagnosticService service = service(gameDir)) {
            Path bundle = service.writeIncident(
                            Map.of(),
                            snapshot(gameDir),
                            CompletableFuture.completedFuture(new LogFlushResult(
                                    LogFlushResult.Status.BUSY,
                                    gameDir.resolve("x.jsonl"),
                                    3L,
                                    0L,
                                    0L,
                                    0L,
                                    "admission_busy")),
                            text -> text)
                    .get(10, TimeUnit.SECONDS);

            String manifest = read(bundle, "manifest.json");
            assertTrue(manifest.contains("\"flush\":\"BUSY\""), manifest);
            assertTrue(manifest.contains("\"partial\":\"writer_busy\""), manifest);
            String incident = read(bundle, "incident.md");
            assertTrue(incident.startsWith("partial: writer_busy"), incident);
            assertFalse(manifest.contains("\"partial\":\"\""));
        }
    }

    @Test
    void aThirdConcurrentExportIsRejectedWhileTwoAreQueued(@TempDir Path gameDir) throws Exception {
        try (DiagnosticService service = service(gameDir)) {
            CompletableFuture<LogFlushResult> never = new CompletableFuture<>();
            CompletableFuture<Path> first = service.writeIncident(Map.of(), snapshot(gameDir), never, text -> text);
            CompletableFuture<Path> second = service.writeIncident(Map.of(), snapshot(gameDir), never, text -> text);
            CompletableFuture<Path> third = service.writeIncident(Map.of(), snapshot(gameDir), never, text -> text);

            ExecutionException rejected = assertThrows(ExecutionException.class, () -> third.get(2, TimeUnit.SECONDS));
            assertEquals(
                    "diagnostic export already in progress", rejected.getCause().getMessage());

            never.cancel(true);
            first.cancel(true);
            second.cancel(true);
        }
    }
}
