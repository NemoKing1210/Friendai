package dev.friendai.infrastructure.persistence;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.foundation.persistence.StoreCodec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * Headless owner-store benchmark. It measures lazy load, one batch flush and sampled single-owner flushes for a
 * population, then appends one row to {@code build/owner-store-benchmark.json} and {@code .md}.
 *
 * <p>This is a test harness, not production code: it uses a direct executor so encode and write happen on the
 * calling thread, and it deletes its own temporary work tree before returning.
 */
final class OwnerStoreBenchmark {
    private static final StoreCodec<List<String>> CODEC = new StoreCodec<>() {
        @Override
        public List<String> load(String json) {
            if (json == null || json.isBlank()) {
                return new ArrayList<>();
            }
            return new ArrayList<>(List.of(json.split(",")));
        }

        @Override
        public String save(List<String> value) {
            return String.join(",", value);
        }

        @Override
        public long revision(List<String> value) {
            return value.size();
        }

        @Override
        public long maxBytes() {
            return 1024 * 1024;
        }
    };
    private static final Path OUTPUT_DIR = Path.of("build");
    private static final Path JSON = OUTPUT_DIR.resolve("owner-store-benchmark.json");
    private static final Path MARKDOWN = OUTPUT_DIR.resolve("owner-store-benchmark.md");
    /**
     * Above this population the per-owner latency pass samples owners instead of flushing every one; a full pass
     * would make {@code queueDirty}'s scan quadratic and dominate the measurement.
     */
    private static final int MAX_LATENCY_SAMPLES = 200;

    private OwnerStoreBenchmark() {}

    record Report(
            long owners,
            long loadNanos,
            long flushNanos,
            long p50FlushNanos,
            long p95FlushNanos,
            long usedHeapBytes,
            long filesWritten) {}

    static synchronized Report run(int owners, Path workDir) throws IOException {
        if (owners < 1) {
            throw new IllegalArgumentException("owners must be positive");
        }
        Files.createDirectories(workDir);
        Report report = measure(owners, workDir);
        writeReports(report);
        deleteTree(workDir);
        return report;
    }

    private static Report measure(int owners, Path workDir) throws IOException {
        List<UUID> ids =
                IntStream.range(0, owners).mapToObj(index -> new UUID(0, index)).toList();
        long[] clock = {0};
        long flushNanos;
        List<Long> latencies = new ArrayList<>();
        long filesWritten;

        try (OwnerFileStore<List<String>> store =
                new OwnerFileStore<>(workDir, CODEC, Runnable::run, () -> clock[0], true)) {
            for (int index = 0; index < owners; index++) {
                store.book(ids.get(index)).add("fact-" + (index % 8));
            }
            clock[0] += OwnerFileStore.FLUSH_INTERVAL_MS + 1;
            long started = System.nanoTime();
            store.flushAll();
            flushNanos = System.nanoTime() - started;

            int samples = Math.min(owners, MAX_LATENCY_SAMPLES);
            for (int sample = 0; sample < samples; sample++) {
                int ownerIndex = (int) ((long) sample * owners / samples);
                store.book(ids.get(ownerIndex)).add("sample-" + sample);
                clock[0] += OwnerFileStore.FLUSH_INTERVAL_MS + 1;
                long sampleStarted = System.nanoTime();
                store.flushAll();
                latencies.add(System.nanoTime() - sampleStarted);
            }
            filesWritten = countFiles(workDir);
        }

        long loadNanos;
        long usedHeapBytes;
        try (OwnerFileStore<List<String>> reloaded =
                new OwnerFileStore<>(workDir, CODEC, Runnable::run, () -> clock[0], true)) {
            long reloadStarted = System.nanoTime();
            for (UUID id : ids) {
                reloaded.book(id);
            }
            loadNanos = System.nanoTime() - reloadStarted;
            usedHeapBytes = usedHeapBytes();
        }

        latencies.sort(Long::compare);
        return new Report(
                owners,
                loadNanos,
                flushNanos,
                percentile(latencies, 50),
                percentile(latencies, 95),
                usedHeapBytes,
                filesWritten);
    }

    private static long percentile(List<Long> sorted, int percentile) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(sorted.size() * percentile / 100.0) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private static long usedHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        System.gc();
        System.gc();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static long countFiles(Path root) throws IOException {
        try (var files = Files.list(root)) {
            return files.filter(Files::isRegularFile).count();
        }
    }

    private static void writeReports(Report report) throws IOException {
        Files.createDirectories(OUTPUT_DIR);
        List<Report> reports = readReports(JSON);
        reports.removeIf(existing -> existing.owners() == report.owners());
        reports.add(report);
        reports.sort(Comparator.comparingLong(Report::owners));

        JsonArray rows = new JsonArray();
        for (Report row : reports) {
            JsonObject object = new JsonObject();
            object.addProperty("owners", row.owners());
            object.addProperty("loadNanos", row.loadNanos());
            object.addProperty("flushNanos", row.flushNanos());
            object.addProperty("p50FlushNanos", row.p50FlushNanos());
            object.addProperty("p95FlushNanos", row.p95FlushNanos());
            object.addProperty("usedHeapBytes", row.usedHeapBytes());
            object.addProperty("filesWritten", row.filesWritten());
            rows.add(object);
        }
        Files.writeString(JSON, rows.toString(), StandardCharsets.UTF_8);
        Files.writeString(MARKDOWN, markdown(reports), StandardCharsets.UTF_8);
    }

    private static List<Report> readReports(Path file) {
        if (!Files.isRegularFile(file)) {
            return new ArrayList<>();
        }
        try {
            JsonArray rows = JsonParser.parseString(Files.readString(file)).getAsJsonArray();
            List<Report> reports = new ArrayList<>();
            for (var row : rows) {
                JsonObject object = row.getAsJsonObject();
                reports.add(new Report(
                        object.get("owners").getAsLong(),
                        object.get("loadNanos").getAsLong(),
                        object.get("flushNanos").getAsLong(),
                        object.get("p50FlushNanos").getAsLong(),
                        object.get("p95FlushNanos").getAsLong(),
                        object.get("usedHeapBytes").getAsLong(),
                        object.get("filesWritten").getAsLong()));
            }
            return reports;
        } catch (IOException | RuntimeException malformed) {
            return new ArrayList<>();
        }
    }

    private static String markdown(List<Report> reports) {
        StringBuilder out = new StringBuilder();
        out.append(
                "| owners | load (ms) | flush total (ms) | p50 (ms) | p95 (ms) | files | approx used heap (MiB) |\n");
        out.append("|---:|---:|---:|---:|---:|---:|---:|\n");
        for (Report row : reports) {
            out.append(String.format(
                    Locale.ROOT,
                    "| %d | %.2f | %.2f | %.2f | %.2f | %d | %.1f |%n",
                    row.owners(),
                    row.loadNanos() / 1_000_000.0,
                    row.flushNanos() / 1_000_000.0,
                    row.p50FlushNanos() / 1_000_000.0,
                    row.p95FlushNanos() / 1_000_000.0,
                    row.filesWritten(),
                    row.usedHeapBytes() / (1024.0 * 1024.0)));
        }
        return out.toString();
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
