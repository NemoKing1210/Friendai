package dev.friendai.test;

import dev.friendai.debug.LogWriterScope;
import dev.friendai.execution.log.TaskLogEvents;
import dev.friendai.foundation.diagnostics.LogCategory;
import dev.friendai.foundation.diagnostics.LogEvents;
import dev.friendai.foundation.diagnostics.LogFilter;
import dev.friendai.foundation.diagnostics.LogFlushResult;
import dev.friendai.foundation.diagnostics.LogLevel;
import dev.friendai.foundation.diagnostics.LogRecord;
import dev.friendai.foundation.diagnostics.LogScan;
import dev.friendai.foundation.diagnostics.LogScanResult;
import dev.friendai.foundation.diagnostics.LogSettings;
import dev.friendai.foundation.diagnostics.ScanLimits;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Proves the durable log writes, routes, prunes, honours off and redacts, without touching the world. */
public class LoggingTests {
    private static final long NOW = 1_700_000_000_000L;
    private static final String DAY = "2023-11-14";

    private static Path root(GameTestHelper h, String name) {
        try {
            Path root = Path.of("build", "gametest-logging", name);
            if (Files.exists(root)) {
                try (Stream<Path> paths = Files.walk(root)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(path);
                    }
                }
            }
            Files.createDirectories(root);
            return root;
        } catch (IOException failure) {
            h.fail("Could not prepare " + name + ": " + failure);
            return Path.of("build", "gametest-logging", name);
        }
    }

    private static LogSettings settings(LogLevel level) {
        return new LogSettings(level, Set.of(), 10_000_000L, 100_000_000L, 30, 20, true);
    }

    private static LogWriterServiceHolder writer(Path root, LogSettings settings, UnaryOperator<String> redactor) {
        return new LogWriterServiceHolder(new dev.friendai.debug.LogWriterService(
                root,
                LogWriterScope.SERVER,
                "7f3c",
                "New World",
                "0.192.0",
                "26.3",
                uuid -> "",
                settings,
                redactor,
                () -> NOW,
                64));
    }

    private record LogWriterServiceHolder(dev.friendai.debug.LogWriterService service) implements AutoCloseable {
        @Override
        public void close() {
            service.close();
        }
    }

    private static List<Path> files(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> paths = Files.walk(dir)) {
            return paths.filter(path -> path.toString().endsWith(".jsonl")).toList();
        }
    }

    @GameTest
    public void server_logs_land_inside_the_world_folder(GameTestHelper h) {
        var server = h.getLevel().getServer();
        var runtime = dev.friendai.server.runtime.ServerRuntimeRegistry.getOrCreate(server);
        Path expected = dev.friendai.debug.LogPaths.worldRoot(
                server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT));
        Path sharedRoot = dev.friendai.debug.LogPaths.gameRoot(
                net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir());
        var fileLog = runtime.fileLog();
        h.assertTrue(
                fileLog.root().toAbsolutePath().equals(expected.toAbsolutePath()),
                "writer root " + fileLog.root() + " is not " + expected);
        h.assertFalse(
                fileLog.root().toAbsolutePath().equals(sharedRoot.toAbsolutePath()),
                "the server writer must not use the shared game root " + sharedRoot);
        UUID owner = UUID.randomUUID();
        Path ownerRoot = dev.friendai.debug.LogPaths.ownerRoot(
                server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT), owner);
        h.assertTrue(
                runtime.sessions().logRootFor(owner).toAbsolutePath().equals(ownerRoot.toAbsolutePath()),
                "logs_search root " + runtime.sessions().logRootFor(owner) + " is not " + ownerRoot);
        h.succeed();
    }

    @GameTest
    public void writes_a_boot_header_and_the_record(GameTestHelper h) throws IOException {
        Path root = root(h, "header");
        try (LogWriterServiceHolder writer = writer(root, settings(LogLevel.INFO), text -> text)) {
            writer.service()
                    .offer(new LogRecord(
                            0L,
                            NOW,
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
                            "booted",
                            Map.of(),
                            null));
            writer.service().flushNow().join();
        }
        List<Path> files = files(root.resolve("server").resolve(DAY));
        h.assertTrue(files.size() == 1, "expected one server file, found " + files);
        List<String> lines = Files.readAllLines(files.get(0), StandardCharsets.UTF_8);
        h.assertTrue(lines.get(0).contains("\"kind\":\"header\""), "first line must be the header");
        h.assertTrue(lines.get(1).contains("booted"), "the record must follow the header");
        h.succeed();
    }

    @GameTest
    public void a_task_event_carries_its_task_id(GameTestHelper h) throws IOException {
        Path root = root(h, "task");
        UUID owner = UUID.randomUUID();
        try (LogWriterServiceHolder writer = writer(root, settings(LogLevel.INFO), text -> text)) {
            writer.service().offer(TaskLogEvents.start(owner, UUID.randomUUID(), "s", 42L, 3L, 9L, "mine_block_at"));
            writer.service().flushNow().join();
        }
        List<Path> files =
                files(root.resolve("owners").resolve(owner.toString()).resolve(DAY));
        h.assertTrue(files.size() == 1, "expected one owner file, found " + files);
        LogScanResult scan = LogScan.scan(files.get(0), ScanLimits.DEFAULT, LogFilter.all(), 50, false);
        boolean found = scan.records().stream()
                .anyMatch(record -> record.task() == 42L && LogEvents.TASK_START.equals(record.event()));
        h.assertTrue(found, "the task record must keep its id");
        h.succeed();
    }

    @GameTest
    public void retention_prunes_old_files(GameTestHelper h) throws IOException {
        Path root = root(h, "retention");
        Path day = root.resolve("server").resolve("2023-11-01");
        Files.createDirectories(day);
        Path old = day.resolve("boot-old.jsonl");
        Files.writeString(old, "{}\n", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(old, FileTime.fromMillis(NOW - 2 * 86_400_000L));
        LogSettings oneDay = new LogSettings(LogLevel.INFO, Set.of(), 10_000_000L, 100_000_000L, 1, 20, true);
        try (LogWriterServiceHolder writer = writer(root, oneDay, text -> text)) {
            writer.service()
                    .offer(new LogRecord(
                            0L,
                            NOW,
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
                            "booted",
                            Map.of(),
                            null));
            writer.service().flushNow().join();
        }
        h.assertTrue(!Files.exists(old), "the file outside the retention window must be pruned");
        h.succeed();
    }

    @GameTest
    public void off_writes_nothing(GameTestHelper h) throws IOException {
        Path root = root(h, "off");
        try (LogWriterServiceHolder writer = writer(root, settings(null), text -> text)) {
            writer.service()
                    .offer(new LogRecord(
                            0L,
                            NOW,
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
                            "booted",
                            Map.of(),
                            null));
            LogFlushResult barrier = writer.service().flushStatus().join();
            h.assertTrue(
                    barrier.status() == LogFlushResult.Status.DISABLED,
                    "off must report a disabled flush, not a durable one: " + barrier);
        }
        h.assertTrue(files(root).isEmpty(), "off must not create a log file");
        h.succeed();
    }

    @GameTest
    public void a_secret_never_reaches_the_file(GameTestHelper h) throws IOException {
        Path root = root(h, "redaction");
        try (LogWriterServiceHolder writer =
                writer(root, settings(LogLevel.INFO), text -> text.replace("SECRET", "***"))) {
            writer.service()
                    .offer(new LogRecord(
                            0L,
                            NOW,
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
                            "key SECRET here",
                            Map.of(),
                            null));
            writer.service().flushNow().join();
        }
        List<Path> files = files(root.resolve("server").resolve(DAY));
        h.assertTrue(files.size() == 1, "expected one server file, found " + files);
        String text = Files.readString(files.get(0), StandardCharsets.UTF_8);
        h.assertTrue(text.contains("***"), "the redaction marker must be present");
        h.assertTrue(!text.contains("SECRET"), "the secret must never be written");
        h.succeed();
    }
}
