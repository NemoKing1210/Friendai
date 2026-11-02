package dev.friendai.debug;

import dev.friendai.foundation.diagnostics.LogCategory;
import dev.friendai.foundation.diagnostics.LogDigest;
import dev.friendai.foundation.diagnostics.LogEvents;
import dev.friendai.foundation.diagnostics.LogFileHeader;
import dev.friendai.foundation.diagnostics.LogFileInfo;
import dev.friendai.foundation.diagnostics.LogFileKind;
import dev.friendai.foundation.diagnostics.LogFilter;
import dev.friendai.foundation.diagnostics.LogJson;
import dev.friendai.foundation.diagnostics.LogLevel;
import dev.friendai.foundation.diagnostics.LogNames;
import dev.friendai.foundation.diagnostics.LogRecord;
import dev.friendai.foundation.diagnostics.LogRetention;
import dev.friendai.foundation.diagnostics.LogScan;
import dev.friendai.foundation.diagnostics.LogScanResult;
import dev.friendai.foundation.diagnostics.LogSettings;
import dev.friendai.foundation.diagnostics.LogSink;
import dev.friendai.foundation.diagnostics.ScanLimits;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;

/**
 * Owns the durable side of the log: one daemon thread writes records into per-stream JSONL files, rotates at the
 * size limit, prunes by age, session count and total size, and writes a digest beside a closed file. The server
 * thread only offers a record; every failure disables this writer instead of reaching the game.
 */
public final class LogWriterService implements LogSink, AutoCloseable {
    public static final int DEFAULT_QUEUE_CAPACITY = 8192;
    private static final long FLUSH_INTERVAL_MS = 1000L;
    private static final int MAX_DIGEST_CHARS = 256 * 1024;
    private static final String THREAD_NAME = "friendai-log-writer";
    private static final String README = """
            # Friendai logs

            One JSON object per line. The first line of every file is a header (`"kind":"header"`) with the
            schema, boot, mod and Minecraft versions, the owner and the active level.

            Tree:
            - `server/<date>/boot-<boot>.jsonl` — server lifecycle and bridged logger warnings/errors.
            - `owners/<uuid>/<date>/session-<time>-<boot>.jsonl` — one owner's brain, tool, task and queue records.
            - `client/<date>/client-<boot>.jsonl` — client-side records.
            - `diagnostics/` — `/friendai diagnose` bundles.

            Fields: `seq`, `timeMillis`, `level` (trace|debug|info|warn|error), `category`, `kind`, `event`,
            `owner`, `companion`, `session`, `turn`, `plan`, `job`, `task`, `toolCall`, `cause`, `durationMs`,
            `msg`, `data`, `error`. `cause` is the `seq` of the record that caused this one, so a chain is followed
            backwards from an error to the turn that started it. A blank `cause` or an unknown one is a root.

            The event names are a closed set (`turn.start`, `tool.call`, `task.finish`, `llm.error`, `log.dropped`,
            ...). `logs_search` reads the same files for the companion; the settings screen owns level, categories
            and retention. `.md` files are generated digests; delete them and they are rebuilt from the JSONL.

            Examples:
            `grep '"level":"error"' owners/*/*/*.jsonl`
            `jq -c 'select(.task==42)' owners/<uuid>/<date>/*.jsonl`
            `jq -r 'select(.cause==1234) | [.seq,.event,.msg] | @tsv' owners/<uuid>/<date>/*.jsonl`
            """;

    private final Path root;
    private final LogWriterScope scope;
    private final String boot;
    private final String modVersion;
    private final String minecraftVersion;
    private final Function<UUID, String> ownerNames;
    private final UnaryOperator<String> redactor;
    private final LongSupplier clock;
    private final long startedMillis;
    private final BlockingQueue<Object> queue;
    private final Thread thread;
    private final Map<Path, OpenFile> open = new HashMap<>();
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private volatile LogSettings settings;
    private volatile boolean closed;
    private volatile boolean failed;
    private volatile Path currentFile;
    private long droppedSinceFlush;
    private long lastFlush;
    private boolean readmeWritten;

    public LogWriterService(
            Path root,
            LogWriterScope scope,
            String boot,
            String modVersion,
            String minecraftVersion,
            Function<UUID, String> ownerNames,
            LogSettings settings,
            UnaryOperator<String> redactor,
            LongSupplier clock) {
        this(
                root,
                scope,
                boot,
                modVersion,
                minecraftVersion,
                ownerNames,
                settings,
                redactor,
                clock,
                DEFAULT_QUEUE_CAPACITY);
    }

    public LogWriterService(
            Path root,
            LogWriterScope scope,
            String boot,
            String modVersion,
            String minecraftVersion,
            Function<UUID, String> ownerNames,
            LogSettings settings,
            UnaryOperator<String> redactor,
            LongSupplier clock,
            int queueCapacity) {
        this.root = root;
        this.scope = scope;
        this.boot = boot;
        this.modVersion = modVersion;
        this.minecraftVersion = minecraftVersion;
        this.ownerNames = ownerNames == null ? uuid -> "" : ownerNames;
        this.settings = settings == null ? LogSettings.off() : settings;
        this.redactor = redactor == null ? text -> text : redactor;
        this.clock = clock == null ? System::currentTimeMillis : clock;
        this.startedMillis = this.clock.getAsLong();
        this.queue = new ArrayBlockingQueue<>(Math.max(1, queueCapacity));
        this.thread = new Thread(this::run, THREAD_NAME);
        this.thread.setDaemon(true);
        this.thread.start();
        this.lastFlush = this.startedMillis;
    }

    @Override
    public void accept(LogRecord record) {
        offer(record);
    }

    /** Never blocks and never throws; a full queue counts as a dropped record. */
    public boolean offer(LogRecord record) {
        if (record == null || closed) return false;
        if (queue.offer(record)) {
            accepted.incrementAndGet();
            return true;
        }
        dropped.incrementAndGet();
        droppedSinceFlush++;
        return false;
    }

    public void update(LogSettings settings) {
        if (settings != null) this.settings = settings;
    }

    public LogWriterStats stats() {
        return new LogWriterStats(accepted.get(), dropped.get(), written.get(), errors.get(), currentFile);
    }

    /** Waits until everything offered before the call is written and flushed. */
    public CompletableFuture<Path> flushNow() {
        CompletableFuture<Path> done = new CompletableFuture<>();
        if (!queue.offer(new Flush(done))) {
            done.complete(currentFile);
        }
        return done;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            flushNow().get(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // A closed writer must not throw; the remaining files are closed below.
        }
        try {
            thread.join(5000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        closeAll();
    }

    private void run() {
        while (!closed || !queue.isEmpty()) {
            Object item;
            try {
                item = queue.poll(200L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
            if (item instanceof LogRecord record) {
                writeSafely(record);
            } else if (item instanceof Flush flush) {
                flush();
                flush.done().complete(currentFile);
            } else {
                flushIfDue();
            }
        }
        flush();
        closeAll();
    }

    private void writeSafely(LogRecord record) {
        if (failed) return;
        try {
            write(record);
        } catch (IOException failure) {
            errors.incrementAndGet();
            failed = true;
            closeQuietly();
        }
    }

    private void write(LogRecord record) throws IOException {
        if (settings.level() == null) return;
        Path path = pathFor(record);
        if (path == null) return;
        OpenFile file = open.get(path);
        if (file == null) {
            file = open(path, record.owner());
            if (file == null) return;
        }
        String line = LogJson.encode(record);
        int bytes = line.getBytes(StandardCharsets.UTF_8).length + 1;
        if (file.bytes > file.headerBytes && file.bytes + bytes > settings.maxFileBytes()) {
            file = rotate(path, file, record.owner());
        }
        file.writer.write(line);
        file.writer.write('\n');
        file.bytes += bytes;
        file.pending++;
        currentFile = path;
        if (file.bytes >= settings.maxFileBytes()) {
            rotate(path, file, record.owner());
        }
    }

    private Path pathFor(LogRecord record) {
        String day = LogNames.dayDir(clock.getAsLong());
        if (scope == LogWriterScope.CLIENT) {
            if (record.category() != LogCategory.CLIENT) return null;
            return root.resolve("client")
                    .resolve(day)
                    .resolve(LogNames.file(LogFileKind.CLIENT, null, boot, startedMillis));
        }
        if (record.category() == LogCategory.CLIENT) return null;
        if (record.owner() != null) {
            return root.resolve("owners")
                    .resolve(record.owner().toString())
                    .resolve(day)
                    .resolve(LogNames.file(LogFileKind.SESSION, record.owner(), boot, startedMillis));
        }
        return root.resolve("server").resolve(day).resolve(LogNames.file(LogFileKind.BOOT, null, boot, startedMillis));
    }

    private OpenFile open(Path path, UUID owner) throws IOException {
        Files.createDirectories(path.getParent());
        if (!readmeWritten) {
            writeReadme();
        }
        prune();
        String header = LogJson.encodeHeader(header(owner));
        BufferedWriter writer = Files.newBufferedWriter(
                path, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        OpenFile file = new OpenFile(path, writer);
        file.headerBytes = header.getBytes(StandardCharsets.UTF_8).length + 1;
        file.bytes = file.headerBytes;
        file.writer.write(header);
        file.writer.write('\n');
        open.put(path, file);
        currentFile = path;
        return file;
    }

    private LogFileHeader header(UUID owner) {
        List<String> categories = new ArrayList<>();
        for (LogCategory category : settings.categories()) {
            categories.add(category.token());
        }
        categories.sort(String::compareTo);
        return new LogFileHeader(
                1,
                boot,
                modVersion,
                minecraftVersion,
                owner,
                owner == null ? "" : ownerNames.apply(owner),
                settings.level() == null ? "off" : settings.level().token(),
                categories,
                startedMillis);
    }

    private OpenFile rotate(Path path, OpenFile file, UUID owner) throws IOException {
        flush(file);
        file.writer.close();
        open.remove(path);
        if (settings.digest()) writeDigest(path);
        int part = 2;
        Path next;
        do {
            next = path.resolveSibling(LogNames.part(path.getFileName().toString(), part++));
        } while (Files.exists(next));
        return open(next, owner);
    }

    private void flushIfDue() {
        long now = clock.getAsLong();
        if (now - lastFlush >= FLUSH_INTERVAL_MS) {
            flush();
        }
    }

    private void flush() {
        if (settings.level() == null) {
            droppedSinceFlush = 0;
            return;
        }
        if (droppedSinceFlush > 0 && !failed) {
            long count = droppedSinceFlush;
            droppedSinceFlush = 0;
            LogRecord marker = new LogRecord(
                    0L,
                    clock.getAsLong(),
                    LogLevel.WARN,
                    LogCategory.SERVER,
                    null,
                    LogEvents.LOG_DROPPED,
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
                    "dropped " + count + " records",
                    Map.of("count", Long.toString(count)),
                    null);
            try {
                write(marker);
            } catch (IOException ignored) {
                errors.incrementAndGet();
                failed = true;
            }
        }
        for (OpenFile file : open.values()) {
            flush(file);
        }
        lastFlush = clock.getAsLong();
    }

    private void flush(OpenFile file) {
        try {
            file.writer.flush();
            written.addAndGet(file.pending);
            file.pending = 0;
        } catch (IOException failure) {
            errors.incrementAndGet();
            failed = true;
        }
    }

    private void prune() {
        List<Path> roots = new ArrayList<>();
        if (scope == LogWriterScope.SERVER) {
            roots.add(root.resolve("server"));
            roots.add(root.resolve("owners"));
        } else {
            roots.add(root.resolve("client"));
        }
        List<LogFileInfo> files = new ArrayList<>();
        for (Path stream : roots) {
            if (!Files.isDirectory(stream)) continue;
            try (var paths = Files.walk(stream)) {
                paths.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".jsonl"))
                        .forEach(path -> {
                            try {
                                BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
                                files.add(new LogFileInfo(
                                        path,
                                        attributes.size(),
                                        attributes.lastModifiedTime().toMillis(),
                                        ownerOf(path),
                                        underOwners(path) ? LogFileKind.SESSION : LogFileKind.BOOT));
                            } catch (IOException ignored) {
                                // A file that cannot be read is left alone.
                            }
                        });
            } catch (IOException ignored) {
                // Pruning is best effort.
            }
        }
        Set<Path> active = new HashSet<>(open.keySet());
        List<Path> remove = LogRetention.plan(
                files,
                new LogRetention.Limits(
                        settings.maxTotalBytes(), settings.retentionDays(), settings.maxSessionsPerOwner()),
                clock.getAsLong(),
                active);
        for (Path path : remove) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // A locked or in-use file is retried next time.
            }
        }
    }

    private static boolean underOwners(Path path) {
        for (Path part : path) {
            if (part.toString().equals("owners")) return true;
        }
        return false;
    }

    private static UUID ownerOf(Path path) {
        for (Path part : path) {
            if (part.toString().equals("owners")) continue;
            try {
                return UUID.fromString(part.toString());
            } catch (IllegalArgumentException notAUuid) {
                // Keep looking.
            }
        }
        return null;
    }

    private void writeReadme() {
        Path readme = root.resolve("README.md");
        if (Files.exists(readme)) {
            readmeWritten = true;
            return;
        }
        try {
            Files.createDirectories(root);
            Files.writeString(readme, README, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // The writer still works without a README; the next open retries.
        }
        readmeWritten = true;
    }

    private void writeDigest(Path path) {
        if (!settings.digest() || !Files.isRegularFile(path)) return;
        try {
            long size = Files.size(path);
            LogScanResult scan = LogScan.scan(
                    path,
                    new ScanLimits((int) Math.min(Integer.MAX_VALUE - 8L, Math.max(1L, size)), 1_000_000),
                    LogFilter.all(),
                    1_000_000,
                    false);
            String first = Files.readAllLines(path, StandardCharsets.UTF_8).stream()
                    .findFirst()
                    .orElse("");
            LogFileHeader header = LogJson.decodeHeader(first)
                    .orElse(new LogFileHeader(
                            1, boot, modVersion, minecraftVersion, null, "", "off", List.of(), startedMillis));
            String text = LogDigest.render(header, scan.records(), 0L, scan.skippedLine(), MAX_DIGEST_CHARS);
            Files.writeString(
                    path.resolveSibling(LogNames.digestName(path.getFileName().toString())),
                    text,
                    StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            errors.incrementAndGet();
        }
    }

    private void closeQuietly() {
        for (OpenFile file : open.values()) {
            try {
                file.writer.close();
            } catch (IOException ignored) {
                // Already failing.
            }
        }
        open.clear();
    }

    private void closeAll() {
        List<Path> paths = new ArrayList<>(open.keySet());
        for (Path path : paths) {
            OpenFile file = open.remove(path);
            if (file == null) continue;
            flush(file);
            try {
                file.writer.close();
            } catch (IOException ignored) {
                errors.incrementAndGet();
            }
            if (settings.digest()) writeDigest(path);
        }
    }

    /** One open stream and its running byte count. */
    private static final class OpenFile {
        final Path path;
        final BufferedWriter writer;
        long bytes;
        long headerBytes;
        long pending;

        OpenFile(Path path, BufferedWriter writer) {
            this.path = path;
            this.writer = writer;
        }
    }

    /** A queue barrier that completes after every earlier record has been written and flushed. */
    private record Flush(CompletableFuture<Path> done) {}
}
