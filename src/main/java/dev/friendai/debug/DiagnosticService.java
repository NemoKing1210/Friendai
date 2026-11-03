package dev.friendai.debug;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.friendai.foundation.diagnostics.Completeness;
import dev.friendai.foundation.diagnostics.DiagnosticBundleWriter;
import dev.friendai.foundation.diagnostics.IncidentTimeline;
import dev.friendai.foundation.diagnostics.LogFileHeader;
import dev.friendai.foundation.diagnostics.LogFilter;
import dev.friendai.foundation.diagnostics.LogFlushResult;
import dev.friendai.foundation.diagnostics.LogHealth;
import dev.friendai.foundation.diagnostics.LogJson;
import dev.friendai.foundation.diagnostics.LogRecord;
import dev.friendai.foundation.diagnostics.LogScan;
import dev.friendai.foundation.diagnostics.LogScanResult;
import dev.friendai.foundation.diagnostics.ScanLimits;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

/**
 * Owns the diagnostic writer thread. A bundle is collected on the server thread and written here, so a slow disk
 * never stalls the server; {@link #close()} drains an in-flight bundle before the server stops.
 *
 * <p>{@link #writeIncident} adds the incident form: it waits up to two seconds for the writer barrier, scans only the
 * owner's own files within one bounded budget, and states exactly what it could not show (busy/failed/disabled flush,
 * truncated scan, open executions) instead of claiming a complete export.
 */
public final class DiagnosticService implements AutoCloseable {
    private static final int MAX_IN_FLIGHT = 2;
    private static final long FLUSH_WAIT_MILLIS = 2_000L;
    private static final int MAX_FILES = 4;
    private static final int MAX_RECORDS = 5_000;
    private static final int MAX_EXCERPT_BYTES = 48 * 1024;
    private static final int MAX_TIMELINE_CHARS = 48 * 1024;
    private static final int MAX_MANIFEST_BYTES = 16 * 1024;
    private static final Gson GSON = new Gson();

    private final Path root;
    private final ExecutorService executor;
    private final AtomicInteger inFlight = new AtomicInteger();

    public DiagnosticService(Path gameDir) {
        this.root = gameDir.resolve("logs").resolve("friendai").resolve("diagnostics");
        this.executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "friendai-diagnostics");
            thread.setDaemon(true);
            return thread;
        });
    }

    public Path root() {
        return root;
    }

    public CompletableFuture<Path> write(Map<String, String> files, UnaryOperator<String> redactor) {
        Map<String, String> snapshot = Map.copyOf(files);
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return DiagnosticBundleWriter.write(root, snapshot, redactor);
                    } catch (IOException error) {
                        throw new CompletionException(error);
                    }
                },
                executor);
    }

    /**
     * Writes one incident bundle: the caller's facts plus {@code manifest.json}, {@code events.jsonl} and
     * {@code incident.md}. At most one bundle is written and one waits; a third request is rejected immediately.
     */
    public CompletableFuture<Path> writeIncident(
            Map<String, String> baseFiles,
            DiagnosticSnapshot snapshot,
            CompletableFuture<LogFlushResult> flush,
            UnaryOperator<String> redactor) {
        if (snapshot == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("a diagnostic snapshot is required"));
        }
        if (inFlight.incrementAndGet() > MAX_IN_FLIGHT) {
            inFlight.decrementAndGet();
            return CompletableFuture.failedFuture(new IllegalStateException("diagnostic export already in progress"));
        }
        Map<String, String> base = baseFiles == null ? Map.of() : Map.copyOf(baseFiles);
        return CompletableFuture.supplyAsync(() -> writeBundle(base, snapshot, flush, redactor), executor)
                .whenComplete((path, error) -> inFlight.decrementAndGet());
    }

    private Path writeBundle(
            Map<String, String> base,
            DiagnosticSnapshot snapshot,
            CompletableFuture<LogFlushResult> flush,
            UnaryOperator<String> redactor) {
        String flushError = "";
        LogFlushResult result = null;
        try {
            if (flush != null) result = flush.get(FLUSH_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            flushError = "flush_timeout";
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            flushError = "flush_interrupted";
        } catch (Exception failed) {
            flushError = "flush_failed";
        }
        String flushStatus = result == null ? "UNKNOWN" : result.status().name();
        String partialReason = flushError.isEmpty() ? partialFor(result) : flushError;

        Scan scan = scan(snapshot.ownerLogRoot());
        LogFileHeader header = new LogFileHeader(
                LogJson.SCHEMA,
                snapshot.boot(),
                snapshot.mod(),
                snapshot.minecraft(),
                snapshot.owner(),
                snapshot.subject(),
                snapshot.level(),
                snapshot.categories(),
                System.currentTimeMillis());
        Completeness measured = new Completeness(
                scan.records().size(),
                0,
                scan.truncated() ? 1 : 0,
                0,
                snapshot.health() == null ? 0L : snapshot.health().dropped(),
                0,
                scan.truncated());
        IncidentTimeline timeline = IncidentTimeline.build(header, scan.records(), measured);
        String incident = timeline.render(MAX_TIMELINE_CHARS);
        if (!partialReason.isEmpty()) {
            incident = "partial: " + partialReason + "\n" + incident;
        }
        String events = events(scan.records());
        String manifest = manifest(snapshot, result, flushError, flushStatus, partialReason, scan, timeline);

        Map<String, String> files = new LinkedHashMap<>(base);
        files.put("manifest.json", manifest);
        files.put("events.jsonl", events);
        files.put("incident.md", incident);
        try {
            return DiagnosticBundleWriter.write(root, files, redactor);
        } catch (IOException error) {
            throw new CompletionException(error);
        }
    }

    private static String partialFor(LogFlushResult result) {
        if (result == null) return "flush_unknown";
        return switch (result.status()) {
            case FLUSHED -> "";
            case BUSY -> "writer_busy";
            case FAILED -> "writer_failed";
            case CLOSED -> "writer_closed";
            case DISABLED -> "log_disabled";
        };
    }

    private static String events(List<LogRecord> records) {
        StringBuilder out = new StringBuilder();
        int bytes = 0;
        for (LogRecord record : records) {
            String line = LogJson.encode(record);
            int size = line.getBytes(StandardCharsets.UTF_8).length + 1;
            if (bytes + size > MAX_EXCERPT_BYTES) break;
            out.append(line).append('\n');
            bytes += size;
        }
        return out.toString();
    }

    private static String manifest(
            DiagnosticSnapshot snapshot,
            LogFlushResult result,
            String flushError,
            String flushStatus,
            String partialReason,
            Scan scan,
            IncidentTimeline timeline) {
        JsonObject manifest = new JsonObject();
        manifest.addProperty("schema", LogJson.SCHEMA);
        manifest.addProperty("created", System.currentTimeMillis());
        manifest.addProperty("subject", snapshot.subject());
        manifest.addProperty(
                "owner", snapshot.owner() == null ? "" : snapshot.owner().toString());
        manifest.addProperty("boot", snapshot.boot());
        manifest.addProperty("scope", "server");
        manifest.addProperty("mod", snapshot.mod());
        manifest.addProperty("minecraft", snapshot.minecraft());
        manifest.addProperty("level", snapshot.level());
        JsonArray categories = new JsonArray();
        for (String category : snapshot.categories()) categories.add(category);
        manifest.add("categories", categories);
        manifest.addProperty("records", scan.records().size());
        manifest.addProperty("scan_limited", scan.truncated());
        manifest.addProperty("flush", flushStatus);
        manifest.addProperty("flush_error", flushError);
        manifest.addProperty("partial", partialReason);
        manifest.addProperty("missing_parents", timeline.completeness().missingParents());
        manifest.addProperty("open_executions", timeline.completeness().openExecutions());
        LogHealth health = snapshot.health();
        if (health != null) {
            manifest.addProperty("health_state", health.state().name());
            manifest.addProperty("accepted", health.accepted());
            manifest.addProperty("written", health.written());
            manifest.addProperty("dropped", health.dropped());
            manifest.addProperty("errors", health.errors());
            manifest.addProperty("depth", health.depth());
            manifest.addProperty("high_water", health.highWater());
        }
        for (Map.Entry<String, String> fact : snapshot.runtimeFacts().entrySet()) {
            manifest.addProperty(fact.getKey(), fact.getValue());
        }
        String json = GSON.toJson(manifest);
        if (json.getBytes(StandardCharsets.UTF_8).length <= MAX_MANIFEST_BYTES) return json;
        return json.substring(0, Math.max(0, MAX_MANIFEST_BYTES - 64)) + "\n[manifest truncated]\n";
    }

    private static Scan scan(Path ownerLogRoot) {
        List<Path> files = new ArrayList<>();
        if (ownerLogRoot != null && Files.isDirectory(ownerLogRoot)) {
            try (var paths = Files.walk(ownerLogRoot)) {
                paths.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".jsonl"))
                        .forEach(files::add);
            } catch (IOException ignored) {
                // An unreadable subtree still produces a bundle with the facts we already have.
            }
        }
        files.sort(Comparator.comparingLong(DiagnosticService::modified).reversed());
        boolean truncated = files.size() > MAX_FILES;
        List<LogRecord> records = new ArrayList<>();
        for (int i = 0; i < Math.min(files.size(), MAX_FILES); i++) {
            LogScanResult result = LogScan.scan(files.get(i), ScanLimits.DEFAULT, LogFilter.all(), MAX_RECORDS, false);
            truncated |= result.truncated();
            records.addAll(result.records());
        }
        records.sort(Comparator.comparingLong(LogRecord::seq));
        if (records.size() > MAX_RECORDS) {
            truncated = true;
            records = new ArrayList<>(records.subList(records.size() - MAX_RECORDS, records.size()));
        }
        return new Scan(records, truncated);
    }

    private static long modified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ignored) {
            return 0L;
        }
    }

    private record Scan(List<LogRecord> records, boolean truncated) {}

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
