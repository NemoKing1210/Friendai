package dev.friendai.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.friendai.foundation.diagnostics.LogCategory;
import dev.friendai.foundation.diagnostics.LogEvents;
import dev.friendai.foundation.diagnostics.LogFlushResult;
import dev.friendai.foundation.diagnostics.LogHealth;
import dev.friendai.foundation.diagnostics.LogLevel;
import dev.friendai.foundation.diagnostics.LogRecord;
import dev.friendai.foundation.diagnostics.LogSettings;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
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

    private static LogWriterService injected(
            Path root, int queue, LogSettings settings, LogWriterService.Io io, ReentrantLock gate) {
        return new LogWriterService(
                root,
                LogWriterScope.SERVER,
                "7f3c",
                "0.160.0",
                "26.3",
                uuid -> "Alex",
                settings,
                text -> text.replace("SECRET", "***"),
                () -> NOW,
                queue,
                io,
                gate);
    }

    private static LogRecord record(long seq, LogCategory category, UUID owner) {
        return record(seq, category, owner, LogLevel.INFO);
    }

    private static LogRecord record(long seq, LogCategory category, UUID owner, LogLevel level) {
        return new LogRecord(
                seq,
                NOW,
                level,
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

    private static void await(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("timed out waiting for " + what);
            }
            Thread.onSpinWait();
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
            assertTrue(
                    Files.exists(file.resolveSibling(dev.friendai.foundation.diagnostics.LogNames.digestName(
                            file.getFileName().toString()))),
                    "each part gets its own digest beside it: " + file);
        }
    }

    @Test
    void aDayChangeClosesThePreviousDaysStream(@TempDir Path root) throws IOException {
        AtomicLong clock = new AtomicLong(NOW);
        LogSettings settings = settings(LogLevel.INFO);
        try (LogWriterService writer = new LogWriterService(
                root,
                LogWriterScope.SERVER,
                "7f3c",
                "0.160.0",
                "26.3",
                uuid -> "Alex",
                settings,
                text -> text,
                clock::get,
                64)) {
            writer.offer(record(1, LogCategory.SERVER, null));
            writer.flushNow().join();
            Path firstDay = root.resolve("server").resolve(DAY);
            clock.addAndGet(86_400_000L);
            writer.offer(record(2, LogCategory.SERVER, null));
            writer.flushNow().join();
            Path firstFile = jsonl(firstDay).getFirst();
            assertTrue(
                    Files.exists(firstFile.resolveSibling(dev.friendai.foundation.diagnostics.LogNames.digestName(
                            firstFile.getFileName().toString()))),
                    "closing yesterday's stream writes its digest, so retention can reclaim it");
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
    void anUnwritableRootReportsAFailedBarrierInsteadOfThrowing(@TempDir Path dir) throws IOException {
        Path root = dir.resolve("not-a-directory");
        Files.writeString(root, "x", StandardCharsets.UTF_8);
        try (LogWriterService writer = writer(root, LogWriterScope.SERVER, settings(LogLevel.INFO), 8)) {
            writer.offer(record(1, LogCategory.SERVER, null));
            LogFlushResult result = writer.flushStatus().join();
            assertEquals(LogFlushResult.Status.FAILED, result.status());
            assertEquals("writer_failed", result.errorCode());
            assertTrue(writer.stats().errors() > 0);
            assertEquals(LogHealth.State.FAILED, writer.health().state());
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

    @Test
    void aBarrierCoversExactlyTheRecordsAdmittedBeforeIt(@TempDir Path root) {
        try (LogWriterService writer = writer(root, LogWriterScope.SERVER, settings(LogLevel.INFO), 64)) {
            assertTrue(writer.offer(record(1, LogCategory.SERVER, null)));
            assertTrue(writer.offer(record(2, LogCategory.SERVER, null)));

            LogFlushResult result = writer.flushStatus().join();

            assertEquals(LogFlushResult.Status.FLUSHED, result.status());
            assertEquals(2L, result.acceptedThrough());
            assertEquals(2L, result.processedThrough());
            assertEquals(2L, result.written());
            assertEquals(0L, result.dropped());
            assertTrue(Files.exists(result.path()));
        }
    }

    @Test
    void aBridgedRecordTakesTheSharedAllocatorSequenceOnce(@TempDir Path root) throws IOException {
        AtomicLong sequence = new AtomicLong(100L);
        try (LogWriterService writer = writer(root, LogWriterScope.SERVER, settings(LogLevel.INFO), 64)) {
            writer.useSequenceSource(sequence::incrementAndGet);
            writer.offer(record(0L, LogCategory.SERVER, null));
            assertEquals(
                    LogFlushResult.Status.FLUSHED, writer.flushStatus().join().status());
        }
        String text =
                Files.readString(jsonl(root.resolve("server").resolve(DAY)).get(0), StandardCharsets.UTF_8);
        assertTrue(text.contains("\"seq\":101"), "a bridged record must carry one pipeline sequence: " + text);
        assertFalse(text.contains("\"seq\":0"), "no written record may keep sequence zero");
        assertEquals(101L, sequence.get());
    }

    @Test
    void aBarrierDoesNotCoverRecordsAdmittedAfterIt(@TempDir Path root) {
        try (LogWriterService writer = writer(root, LogWriterScope.SERVER, settings(LogLevel.INFO), 64)) {
            assertTrue(writer.offer(record(1, LogCategory.SERVER, null)));

            java.util.concurrent.CompletableFuture<LogFlushResult> barrier = writer.flushStatus();
            assertTrue(writer.offer(record(2, LogCategory.SERVER, null)), "a later record is still admitted");

            LogFlushResult result = barrier.join();
            assertEquals(LogFlushResult.Status.FLUSHED, result.status());
            assertEquals(1L, result.acceptedThrough(), "the barrier covers only what preceded it");
            assertEquals(1L, result.processedThrough());
        }
    }

    @Test
    void aDisabledWriterReportsDisabledRatherThanADurableFlush(@TempDir Path root) throws IOException {
        try (LogWriterService writer = writer(root, LogWriterScope.SERVER, settings(null), 64)) {
            writer.offer(record(1, LogCategory.SERVER, null));

            LogFlushResult result = writer.flushStatus().join();

            assertEquals(LogFlushResult.Status.DISABLED, result.status());
            assertFalse(result.durable());
            assertTrue(jsonl(root).isEmpty());
        }
    }

    @Test
    void flushDueTriggersOnTheDeadlineOrThePendingBatch() {
        assertFalse(LogWriterService.flushDue(999L, 0L, 0L));
        assertTrue(LogWriterService.flushDue(1000L, 0L, 0L));
        assertFalse(LogWriterService.flushDue(0L, 0L, 255L));
        assertTrue(LogWriterService.flushDue(0L, 0L, 256L));
    }

    @Test
    void theDeadlineFlushesUnderContinuousAdmission(@TempDir Path root) {
        AtomicLong now = new AtomicLong(NOW);
        LogWriterService writer = new LogWriterService(
                root,
                LogWriterScope.SERVER,
                "7f3c",
                "0.160.0",
                "26.3",
                uuid -> "Alex",
                settings(LogLevel.INFO),
                text -> text,
                now::get,
                64);
        try {
            assertTrue(writer.offer(record(1, LogCategory.SERVER, null)));
            await("the first record to be processed", () -> writer.health().processed() == 1L);
            now.addAndGet(LogWriterService.FLUSH_INTERVAL_MS);
            assertTrue(writer.offer(record(2, LogCategory.SERVER, null)));
            await("the deadline to flush both records", () -> writer.health().written() >= 2L);
        } finally {
            writer.close();
        }
    }

    @Test
    void aFullBatchFlushesWithoutABarrier(@TempDir Path root) {
        LogWriterService writer = writer(root, LogWriterScope.SERVER, settings(LogLevel.INFO), 512);
        try {
            for (int i = 0; i < 300; i++) {
                writer.offer(record(i + 1, LogCategory.SERVER, null));
            }
            await(
                    "the pending batch to flush",
                    () -> writer.health().written() >= LogWriterService.PENDING_FLUSH_RECORDS);
        } finally {
            writer.close();
        }
    }

    @Test
    void aSaturatedQueueDropsDetailBeforeOrdinaryRecords(@TempDir Path root) throws Exception {
        BlockingIo io = new BlockingIo();
        LogWriterService writer = injected(root, 32, settings(LogLevel.INFO), io, new ReentrantLock());
        try {
            assertTrue(writer.offer(record(0, LogCategory.SERVER, null)));
            assertTrue(io.entered.await(5, TimeUnit.SECONDS), "the writer must reach the injected open");

            int detail = 0;
            while (writer.offer(record(100 + detail, LogCategory.SERVER, null, LogLevel.DEBUG))) {
                detail++;
            }
            assertEquals(24, detail, "32 queue slots - 4 control - 4 detail");
            int ordinary = 0;
            while (writer.offer(record(ordinary + 1, LogCategory.SERVER, null))) {
                ordinary++;
            }
            assertEquals(4, ordinary);
            assertEquals(28, writer.health().depth());
        } finally {
            io.release.countDown();
            writer.close();
        }
    }

    @Test
    void acceptedRecordsKeepTheirAdmissionOrder(@TempDir Path root) throws IOException {
        try (LogWriterService writer = writer(root, LogWriterScope.SERVER, settings(LogLevel.INFO), 64)) {
            for (int i = 0; i < 50; i++) {
                assertTrue(writer.offer(record(i + 1, LogCategory.SERVER, null)));
            }
            assertEquals(
                    LogFlushResult.Status.FLUSHED, writer.flushStatus().join().status());
        }
        List<String> lines =
                Files.readAllLines(jsonl(root.resolve("server").resolve(DAY)).get(0), StandardCharsets.UTF_8);
        List<String> messages = new ArrayList<>();
        for (String line : lines) {
            int at = line.indexOf("message ");
            if (at >= 0) {
                messages.add(line.substring(at, line.indexOf('"', at)));
            }
        }
        assertEquals(50, messages.size());
        for (int i = 0; i < messages.size(); i++) {
            assertEquals("message " + (i + 1), messages.get(i));
        }
    }

    @Test
    void concurrentProducersAndDropsAreCountedExactly(@TempDir Path root) throws Exception {
        int threads = 4;
        int perThread = 1000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        LogWriterService writer = writer(root, LogWriterScope.SERVER, settings(LogLevel.INFO), 64);
        try {
            List<java.util.concurrent.Future<Integer>> results = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int base = t * perThread;
                results.add(pool.submit(() -> {
                    int accepted = 0;
                    for (int i = 0; i < perThread; i++) {
                        if (writer.offer(record(base + i + 1, LogCategory.SERVER, null))) accepted++;
                    }
                    return accepted;
                }));
            }
            int accepted = 0;
            for (var result : results) {
                accepted += result.get();
            }
            LogHealth health = writer.health();
            assertEquals(accepted, health.accepted());
            assertEquals(threads * perThread, health.accepted() + health.dropped());

            LogFlushResult barrier = writer.flushStatus().join();
            assertEquals(LogFlushResult.Status.FLUSHED, barrier.status());
            assertEquals(health.accepted(), barrier.acceptedThrough());
            assertEquals(barrier.acceptedThrough(), barrier.processedThrough());
        } finally {
            pool.shutdownNow();
            writer.close();
        }
    }

    @Test
    void aBarrierIsBusyWhenItCannotTakeTheAdmissionGate(@TempDir Path root) throws Exception {
        ReentrantLock gate = new ReentrantLock();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            gate.lock();
            locked.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                gate.unlock();
            }
        });
        holder.start();
        assertTrue(locked.await(5, TimeUnit.SECONDS));
        LogWriterService writer = injected(root, 64, settings(LogLevel.INFO), null, gate);
        try {
            LogFlushResult result = writer.flushStatus().join();
            assertEquals(LogFlushResult.Status.BUSY, result.status());
            assertEquals("admission_busy", result.errorCode());
            assertFalse(writer.offer(record(1, LogCategory.SERVER, null)));
            assertEquals(1L, writer.health().dropped());

            assertThrows(CompletionException.class, () -> writer.flushNow().join());
        } finally {
            release.countDown();
            holder.join(5000L);
            writer.close();
        }
    }

    @Test
    void aFailedWriterIsVisibleAndRejectsFurtherRecords(@TempDir Path root) {
        LogWriterService.Io broken = new LogWriterService.Io() {
            @Override
            public Writer open(Path path) throws IOException {
                throw new IOException("disk full");
            }

            @Override
            public void flush(Writer writer) {}

            @Override
            public void close(Writer writer) {}
        };
        LogWriterService writer = injected(root, 64, settings(LogLevel.INFO), broken, null);
        try {
            assertTrue(writer.offer(record(1, LogCategory.SERVER, null)));
            LogFlushResult result = writer.flushStatus().join();
            assertEquals(LogFlushResult.Status.FAILED, result.status());
            assertEquals(LogHealth.State.FAILED, writer.health().state());
            assertTrue(writer.health().errors() >= 1L);
            assertFalse(writer.offer(record(2, LogCategory.SERVER, null)));
            assertEquals(1L, writer.health().accepted());
        } finally {
            writer.close();
            writer.close();
        }
    }

    @Test
    void aLossMarkerThatCannotBeWrittenKeepsTheLossCount(@TempDir Path root) {
        // Queue capacity 1 reserves its only slot for control items, so every record is dropped, and the flush opens
        // the file to write the loss marker: header line is call 1, its newline 2, the marker line 3 fails.
        LogWriterService writer = injected(root, 1, settings(LogLevel.INFO), failingWriterIo(3), null);
        try {
            for (int i = 0; i < 10; i++) {
                writer.offer(record(i + 1, LogCategory.SERVER, null));
            }
            assertEquals(
                    LogFlushResult.Status.FAILED, writer.flushStatus().join().status());
            LogHealth health = writer.health();
            assertEquals(LogHealth.State.FAILED, health.state());
            assertEquals(0L, health.accepted());
            assertEquals(10L, health.dropped());
            assertEquals(10L, health.droppedSinceFlush(), "a failed marker must not erase the loss count");
        } finally {
            writer.close();
        }
    }

    @Test
    void aTimedOutCloseLeavesUnwrittenCountsVisible(@TempDir Path root) throws Exception {
        BlockingIo io = new BlockingIo();
        LogWriterService writer = injected(root, 64, settings(LogLevel.INFO), io, null);
        try {
            writer.offer(record(1, LogCategory.SERVER, null));
            writer.offer(record(2, LogCategory.SERVER, null));
            assertTrue(io.entered.await(5, TimeUnit.SECONDS));
            writer.close();

            LogHealth health = writer.health();
            assertEquals(LogHealth.State.CLOSED, health.state());
            assertTrue(health.accepted() > health.written(), "unwritten records must stay visible");
            assertTrue(health.accepted() >= 2L);
        } finally {
            io.release.countDown();
            await("the worker to drain after release", () -> writer.health().written() >= 2L);
            writer.close();
        }
    }

    private static LogWriterService.Io failingWriterIo(int failAt) {
        return new LogWriterService.Io() {
            @Override
            public Writer open(Path path) {
                return new FailingWriter(failAt);
            }

            @Override
            public void flush(Writer writer) throws IOException {
                writer.flush();
            }

            @Override
            public void close(Writer writer) throws IOException {
                writer.close();
            }
        };
    }

    /** Returns a writer that blocks the first open so admission can be observed against a still queue. */
    private static final class BlockingIo implements LogWriterService.Io {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public Writer open(Path path) throws IOException {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return Files.newBufferedWriter(
                    path, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }

        @Override
        public void flush(Writer writer) throws IOException {
            writer.flush();
        }

        @Override
        public void close(Writer writer) throws IOException {
            writer.close();
        }
    }

    /** Fails on the configured write call and accepts everything else. */
    private static final class FailingWriter extends Writer {
        private final int failAt;
        private int calls;

        FailingWriter(int failAt) {
            this.failAt = failAt;
        }

        @Override
        public void write(char[] buffer, int offset, int length) throws IOException {
            if (++calls == failAt) {
                throw new IOException("disk full");
            }
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
    }
}
