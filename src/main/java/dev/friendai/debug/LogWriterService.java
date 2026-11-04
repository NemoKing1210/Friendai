package dev.friendai.debug;

import dev.friendai.FriendaiMod;
import dev.friendai.foundation.diagnostics.LogCategory;
import dev.friendai.foundation.diagnostics.LogDigest;
import dev.friendai.foundation.diagnostics.LogError;
import dev.friendai.foundation.diagnostics.LogEvents;
import dev.friendai.foundation.diagnostics.LogFileHeader;
import dev.friendai.foundation.diagnostics.LogFileInfo;
import dev.friendai.foundation.diagnostics.LogFileKind;
import dev.friendai.foundation.diagnostics.LogFilter;
import dev.friendai.foundation.diagnostics.LogFlushResult;
import dev.friendai.foundation.diagnostics.LogHealth;
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
import java.io.IOException;
import java.io.Writer;
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
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;

/**
 * Owns the durable side of the log: one daemon thread writes records into per-stream JSONL files, rotates at the
 * size limit, prunes by age, session count and total size, and writes a digest beside a closed file. The server
 * thread only offers a record; every failure disables this writer instead of reaching the game.
 *
 * <p>Admission is one short transaction under {@link #gate}: capacity, tickets and queue insertion happen together,
 * so a barrier taken at that point covers exactly the records admitted before it. A rejected offer has no ticket and
 * is counted as dropped. Control items (barriers, retention) keep a small reserved slice of the queue, and detail
 * traffic keeps its own reserve, so a saturated queue sheds DEBUG/TRACE before ordinary records. Every file
 * operation, including the final close and digest, runs on the writer thread; {@link #close()} only signals and
 * waits, up to five seconds.
 */
public final class LogWriterService implements LogSink, AutoCloseable {
    public static final int DEFAULT_QUEUE_CAPACITY = 8192;
    static final long FLUSH_INTERVAL_MS = 1000L;
    static final int PENDING_FLUSH_RECORDS = 256;
    static final int CONTROL_RESERVE = 8;
    static final int DETAIL_RESERVE = 128;
    static final long SHUTDOWN_MILLIS = 5000L;
    private static final int MAX_DIGEST_CHARS = 256 * 1024;
    private static final String THREAD_NAME = "friendai-log-writer";
    private static final String README = """
            # Friendai logs

            One JSON object per line. The first line of every file is a header (`"kind":"header"`) with the
            schema, boot, mod and Minecraft versions, the owner and the active level.

            Tree (this root is one world: `<world>/friendai/logs/`):
            - `server/<date>/boot-<boot>.jsonl` — server lifecycle and bridged logger warnings/errors.
            - `owners/<uuid>/<date>/session-<time>-<boot>.jsonl` — one owner's brain, tool, task and queue records.
            - `diagnostics/` — `/friendai diagnose` bundles.

            The client writer keeps its own tree at `<gameDir>/logs/friendai/client/`, beside `index.jsonl`,
            which records one line per world load and close.

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

    /** The only file operations the writer performs; tests inject failures through it. */
    interface Io {
        Writer open(Path path) throws IOException;

        void flush(Writer writer) throws IOException;

        void close(Writer writer) throws IOException;
    }

    private static final Io DEFAULT_IO = new Io() {
        @Override
        public Writer open(Path path) throws IOException {
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
    };

    private final Path root;
    private final LogWriterScope scope;
    private final String boot;
    private final String world;
    private final String modVersion;
    private final String minecraftVersion;
    private final Function<UUID, String> ownerNames;
    private final LongSupplier clock;
    private final long startedMillis;
    private final BlockingQueue<Object> queue;
    private final Thread thread;
    private final Map<Path, OpenFile> open = new HashMap<>();
    private final AtomicLong admittedThrough = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong droppedSinceFlush = new AtomicLong();
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final AtomicInteger highWater = new AtomicInteger();
    private final AtomicBoolean closeRequested = new AtomicBoolean();
    private final Io io;
    private final ReentrantLock gate;
    private final int capacity;
    private final int controlReserve;
    private final int detailReserve;
    private volatile UnaryOperator<String> redactor;
    private volatile java.util.function.LongSupplier sequenceSource = () -> 0L;
    private volatile LogSettings settings;
    private volatile boolean closed;
    private volatile boolean failed;
    private volatile String failureCode = "";
    private volatile Path currentFile;
    private volatile long processedThrough;
    private volatile long lastFlush;
    private long nextTicket;
    private long pendingRecords;
    private boolean readmeWritten;

    public LogWriterService(
            Path root,
            LogWriterScope scope,
            String boot,
            String world,
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
                world,
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
            String world,
            String modVersion,
            String minecraftVersion,
            Function<UUID, String> ownerNames,
            LogSettings settings,
            UnaryOperator<String> redactor,
            LongSupplier clock,
            int queueCapacity) {
        this(
                root,
                scope,
                boot,
                world,
                modVersion,
                minecraftVersion,
                ownerNames,
                settings,
                redactor,
                clock,
                queueCapacity,
                DEFAULT_IO,
                new ReentrantLock());
    }

    /** The injection seam: package-private so production always uses the JDK files and a fair gate. */
    LogWriterService(
            Path root,
            LogWriterScope scope,
            String boot,
            String world,
            String modVersion,
            String minecraftVersion,
            Function<UUID, String> ownerNames,
            LogSettings settings,
            UnaryOperator<String> redactor,
            LongSupplier clock,
            int queueCapacity,
            Io io,
            ReentrantLock gate) {
        this.root = root;
        this.scope = scope;
        this.boot = boot;
        this.world = world == null ? "" : world;
        this.modVersion = modVersion;
        this.minecraftVersion = minecraftVersion;
        this.ownerNames = ownerNames == null ? uuid -> "" : ownerNames;
        this.settings = settings == null ? LogSettings.off() : settings;
        this.redactor = redactor == null ? text -> text : redactor;
        this.clock = clock == null ? System::currentTimeMillis : clock;
        this.startedMillis = this.clock.getAsLong();
        this.capacity = Math.max(1, queueCapacity);
        this.controlReserve = capacity >= 64 ? CONTROL_RESERVE : Math.max(1, capacity / 8);
        int wantedDetail = capacity >= 1024 ? DETAIL_RESERVE : Math.max(1, capacity / 8);
        this.detailReserve = Math.min(wantedDetail, Math.max(0, capacity - controlReserve - 1));
        this.queue = new ArrayBlockingQueue<>(this.capacity);
        this.io = io == null ? DEFAULT_IO : io;
        this.gate = gate == null ? new ReentrantLock() : gate;
        this.lastFlush = this.startedMillis;
        this.thread = new Thread(this::run, THREAD_NAME);
        this.thread.setDaemon(true);
        this.thread.start();
    }

    @Override
    public void accept(LogRecord record) {
        offer(record);
    }

    /**
     * Admits one record without blocking or throwing. A closed, failed or busy writer and a queue without room for
     * this class of record all count as a dropped record; the accepted ones keep their admission order.
     */
    public boolean offer(LogRecord record) {
        if (record == null) return false;
        if (closed || failed) {
            drop();
            return false;
        }
        if (!gate.tryLock()) {
            drop();
            return false;
        }
        try {
            if (closed || failed) {
                drop();
                return false;
            }
            if (!hasRoom(record)) {
                drop();
                return false;
            }
            long ticket = nextTicket + 1L;
            if (!queue.offer(new Envelope(record, ticket))) {
                drop();
                return false;
            }
            nextTicket = ticket;
            admittedThrough.set(ticket);
            noteDepth();
            return true;
        } finally {
            gate.unlock();
        }
    }

    public void update(LogSettings settings) {
        if (settings != null) this.settings = settings;
    }

    /**
     * Shares the diagnostic pipeline's sequence allocator, so a record that reaches a sink directly (a loss marker
     * or a bridged logger record) still carries one pipeline sequence instead of zero.
     */
    public void useSequenceSource(java.util.function.LongSupplier source) {
        if (source != null) this.sequenceSource = source;
    }

    /** Installs the runtime secret redactor once the gateway knows the configured keys. */
    public void updateRedactor(UnaryOperator<String> redactor) {
        if (redactor != null) this.redactor = redactor;
    }

    /** The root this writer owns: one world's tree for the server scope, the client tree for the client scope. */
    public Path root() {
        return root;
    }

    public LogWriterStats stats() {
        return new LogWriterStats(admittedThrough.get(), dropped.get(), written.get(), errors.get(), currentFile);
    }

    public LogHealth health() {
        return new LogHealth(
                admittedThrough.get(),
                processedThrough,
                written.get(),
                dropped.get(),
                errors.get(),
                droppedSinceFlush.get(),
                queue.size(),
                highWater.get(),
                lastFlush,
                failed ? LogHealth.State.FAILED : closed ? LogHealth.State.CLOSED : LogHealth.State.OPEN);
    }

    /**
     * Takes a barrier after the records admitted so far. The result distinguishes a real flush from a busy, failed,
     * closed or disabled writer; it never claims durability it did not get.
     */
    public CompletableFuture<LogFlushResult> flushStatus() {
        if (settings.level() == null) {
            return CompletableFuture.completedFuture(new LogFlushResult(
                    LogFlushResult.Status.DISABLED,
                    currentFile,
                    admittedThrough.get(),
                    0L,
                    0L,
                    dropped.get(),
                    "log_disabled"));
        }
        if (closed) {
            return CompletableFuture.completedFuture(new LogFlushResult(
                    LogFlushResult.Status.CLOSED,
                    currentFile,
                    admittedThrough.get(),
                    processedThrough,
                    written.get(),
                    dropped.get(),
                    "writer_closed"));
        }
        if (failed) {
            return CompletableFuture.completedFuture(new LogFlushResult(
                    LogFlushResult.Status.FAILED,
                    currentFile,
                    admittedThrough.get(),
                    processedThrough,
                    written.get(),
                    dropped.get(),
                    failureCode));
        }
        CompletableFuture<LogFlushResult> done = new CompletableFuture<>();
        if (!gate.tryLock()) {
            done.complete(LogFlushResult.busy("admission_busy", currentFile, admittedThrough.get(), dropped.get()));
            return done;
        }
        try {
            if (closed || failed) {
                done.complete(new LogFlushResult(
                        closed ? LogFlushResult.Status.CLOSED : LogFlushResult.Status.FAILED,
                        currentFile,
                        admittedThrough.get(),
                        processedThrough,
                        written.get(),
                        dropped.get(),
                        closed ? "writer_closed" : failureCode));
                return done;
            }
            long covered = admittedThrough.get();
            long droppedAtBarrier = dropped.get();
            if (!queue.offer(new Flush(done, covered, droppedAtBarrier))) {
                done.complete(LogFlushResult.busy("queue_full", currentFile, covered, droppedAtBarrier));
            }
            return done;
        } finally {
            gate.unlock();
        }
    }

    /**
     * The historical signature with corrected failure behavior: a barrier that did not flush completes exceptionally
     * instead of handing back a path that promises a write. New code uses {@link #flushStatus()}.
     */
    public CompletableFuture<Path> flushNow() {
        return flushStatus().thenApply(result -> {
            if (!result.durable()) {
                throw new CompletionException(new IOException("log flush "
                        + result.status()
                        + (result.errorCode().isEmpty() ? "" : ": " + result.errorCode())));
            }
            return result.path();
        });
    }

    @Override
    public void close() {
        if (!closeRequested.compareAndSet(false, true)) {
            joinRemaining(System.nanoTime());
            return;
        }
        LogSinkHub.remove(this);
        long started = System.nanoTime();
        CompletableFuture<LogFlushResult> barrier = flushStatus();
        closed = true;
        try {
            barrier.get(remainingMillis(started), TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            // A timeout leaves the files to the worker; health still reports the unwritten count.
        }
        joinRemaining(started);
    }

    public int pruneNow() {
        if (closed || failed) return 0;
        CompletableFuture<Integer> done = new CompletableFuture<>();
        if (!gate.tryLock()) return 0;
        try {
            if (!queue.offer(new Prune(done))) return 0;
        } finally {
            gate.unlock();
        }
        try {
            return done.get(5, TimeUnit.SECONDS);
        } catch (Exception failure) {
            return 0;
        }
    }

    /** True when the writer should flush now: the deadline elapsed or enough records are pending. */
    static boolean flushDue(long nowMillis, long lastFlushMillis, long pendingRecords) {
        return nowMillis - lastFlushMillis >= FLUSH_INTERVAL_MS || pendingRecords >= PENDING_FLUSH_RECORDS;
    }

    private void run() {
        while (true) {
            Object item;
            try {
                item = queue.poll(200L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
            if (item instanceof Envelope envelope) {
                writeSafely(envelope.record());
                processedThrough = envelope.ticket();
                pendingRecords++;
                noteDepth();
                if (flushDue(clock.getAsLong(), lastFlush, pendingRecords)) {
                    flush();
                }
            } else if (item instanceof Flush flush) {
                flush();
                flush.done().complete(flushResult(flush));
            } else if (item instanceof Prune prune) {
                int removed = prune();
                flush();
                prune.done().complete(removed);
            } else if (closed && queue.isEmpty()) {
                break;
            } else if (flushDue(clock.getAsLong(), lastFlush, pendingRecords)) {
                flush();
            }
        }
        flush();
        closeAll();
    }

    private LogFlushResult flushResult(Flush flush) {
        // The barrier promises exactly the coverage admitted before it; a later admission is not its business.
        long accepted = flush.covered();
        long processed = Math.max(processedThrough, flush.covered());
        if (failed) {
            return new LogFlushResult(
                    LogFlushResult.Status.FAILED,
                    currentFile,
                    accepted,
                    processed,
                    written.get(),
                    flush.droppedAtBarrier(),
                    failureCode);
        }
        return new LogFlushResult(
                LogFlushResult.Status.FLUSHED,
                currentFile,
                accepted,
                processed,
                written.get(),
                flush.droppedAtBarrier(),
                "");
    }

    private void noteDepth() {
        highWater.accumulateAndGet(queue.size(), Math::max);
    }

    private void drop() {
        dropped.incrementAndGet();
        droppedSinceFlush.incrementAndGet();
    }

    private boolean hasRoom(LogRecord record) {
        int limit = capacity - controlReserve - (isDetail(record) ? detailReserve : 0);
        return queue.size() < limit;
    }

    private static boolean isDetail(LogRecord record) {
        return record.level() != null && record.level().ordinal() <= LogLevel.DEBUG.ordinal();
    }

    private void writeSafely(LogRecord record) {
        if (failed) return;
        try {
            write(record);
        } catch (IOException failure) {
            errors.incrementAndGet();
            fail(failure);
            closeQuietly();
        }
    }

    private void write(LogRecord incoming) throws IOException {
        LogRecord record = incoming.seq() == 0L ? withSequence(incoming, sequenceSource.getAsLong()) : incoming;
        if (!settings.allows(record)) return;
        Path path = pathFor(record);
        if (path == null) return;
        OpenFile file = open.get(path);
        if (file == null) {
            file = open(path, record.owner());
            if (file == null) return;
        }
        String line = LogJson.encode(redact(record));
        int bytes = line.getBytes(StandardCharsets.UTF_8).length + 1;
        if (file.bytes > file.headerBytes && file.bytes + bytes > settings.maxFileBytes()) {
            file = rotate(path, file, record.owner());
        }
        file.writer.write(line);
        file.writer.write('\n');
        file.bytes += bytes;
        file.pending++;
        currentFile = file.path;
        if (file.bytes >= settings.maxFileBytes()) {
            rotate(path, file, record.owner());
        }
    }

    private static LogRecord withSequence(LogRecord record, long seq) {
        if (seq == 0L) return record;
        return new LogRecord(
                seq,
                record.timeMillis(),
                record.level(),
                record.category(),
                record.kind(),
                record.event(),
                record.owner(),
                record.companion(),
                record.session(),
                record.turn(),
                record.plan(),
                record.job(),
                record.task(),
                record.toolCall(),
                record.cause(),
                record.durationMs(),
                record.message(),
                record.data(),
                record.error());
    }

    private LogRecord redact(LogRecord record) {
        Map<String, String> data = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> entry : record.data().entrySet()) {
            data.put(redactor.apply(entry.getKey()), redactor.apply(entry.getValue()));
        }
        LogError error = record.error();
        if (error != null) {
            error = new LogError(
                    redactor.apply(error.type()),
                    redactor.apply(error.message()),
                    redactor.apply(error.stack()),
                    error.frames());
        }
        return new LogRecord(
                record.seq(),
                record.timeMillis(),
                record.level(),
                record.category(),
                record.kind(),
                record.event(),
                record.owner(),
                record.companion(),
                record.session(),
                record.turn(),
                record.plan(),
                record.job(),
                record.task(),
                redactor.apply(record.toolCall()),
                record.cause(),
                record.durationMs(),
                redactor.apply(record.message()),
                data,
                error);
    }

    private Path pathFor(LogRecord record) {
        String day = LogNames.dayDir(clock.getAsLong());
        if (scope == LogWriterScope.CLIENT) {
            if (record.category() != LogCategory.CLIENT && record.category() != LogCategory.SPEECH) return null;
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
        Writer writer = io.open(path);
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
                world,
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
        io.close(file.writer);
        open.remove(path);
        if (settings.digest()) writeDigest(path);
        int part = 2;
        Path next;
        do {
            next = path.resolveSibling(LogNames.part(path.getFileName().toString(), part++));
        } while (Files.exists(next));
        OpenFile opened = open(next, owner);
        if (opened == null) return file;
        open.remove(next);
        open.put(path, opened);
        return opened;
    }

    private void flush() {
        if (settings.level() == null) {
            droppedSinceFlush.set(0);
            pendingRecords = 0;
            return;
        }
        if (failed) {
            return;
        }
        long lost = droppedSinceFlush.getAndSet(0);
        if (lost > 0) {
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
                    "dropped " + lost + " records",
                    Map.of("count", Long.toString(lost)),
                    null);
            try {
                write(marker);
            } catch (IOException failure) {
                errors.incrementAndGet();
                droppedSinceFlush.addAndGet(lost);
                fail(failure);
            }
        }
        for (OpenFile file : open.values()) {
            flush(file);
        }
        if (failed) {
            closeQuietly();
        }
        pendingRecords = 0;
        lastFlush = clock.getAsLong();
    }

    private void flush(OpenFile file) {
        try {
            io.flush(file.writer);
            written.addAndGet(file.pending);
            file.pending = 0;
        } catch (IOException failure) {
            errors.incrementAndGet();
            fail(failure);
        }
    }

    private void fail(IOException failure) {
        boolean first = !failed;
        failed = true;
        if (failureCode.isEmpty()) {
            failureCode = "writer_failed";
        }
        if (first) {
            FriendaiMod.LOGGER.warn("File log disabled: {}", failure.toString());
        }
    }

    private int prune() {
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
        Set<Path> active = new HashSet<>();
        for (OpenFile file : open.values()) {
            active.add(file.path);
        }
        List<Path> remove = LogRetention.plan(
                files,
                new LogRetention.Limits(
                        settings.maxTotalBytes(), settings.retentionDays(), settings.maxSessionsPerOwner()),
                clock.getAsLong(),
                active);
        int removed = 0;
        for (Path path : remove) {
            try {
                if (Files.deleteIfExists(path)) removed++;
            } catch (IOException ignored) {
                // A locked or in-use file is retried next time.
            }
        }
        return removed;
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
                            1, boot, world, modVersion, minecraftVersion, null, "", "off", List.of(), startedMillis));
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
                io.close(file.writer);
            } catch (IOException ignored) {
                // Already failing.
            }
        }
        open.clear();
    }

    private void closeAll() {
        List<Path> keys = new ArrayList<>(open.keySet());
        for (Path key : keys) {
            OpenFile file = open.remove(key);
            if (file == null) continue;
            flush(file);
            try {
                io.close(file.writer);
            } catch (IOException ignored) {
                errors.incrementAndGet();
            }
            if (settings.digest()) writeDigest(file.path);
        }
    }

    private void joinRemaining(long started) {
        try {
            thread.join(remainingMillis(started));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static long remainingMillis(long startedNanos) {
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        return Math.max(1L, SHUTDOWN_MILLIS - elapsed);
    }

    /** One open stream and its running byte count. */
    private static final class OpenFile {
        final Path path;
        final Writer writer;
        long bytes;
        long headerBytes;
        long pending;

        OpenFile(Path path, Writer writer) {
            this.path = path;
            this.writer = writer;
        }
    }

    /** One admitted record with the ticket its barrier coverage is expressed in. */
    private record Envelope(LogRecord record, long ticket) {}

    /** A queue barrier that completes after every record admitted before it has been written and flushed. */
    private record Flush(CompletableFuture<LogFlushResult> done, long covered, long droppedAtBarrier) {}

    /** A queue barrier that runs retention on the writer thread and returns how many files it removed. */
    private record Prune(CompletableFuture<Integer> done) {}
}
