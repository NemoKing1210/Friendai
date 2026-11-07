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
    private static final Path SCENARIO_JSON = OUTPUT_DIR.resolve("owner-store-scenarios.json");
    private static final Path SCENARIO_MARKDOWN = OUTPUT_DIR.resolve("owner-store-scenarios.md");
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

    /**
     * One measured scenario: population size, record size and how much of the population is dirty, flushed through
     * the production asynchronous writer. The caller-thread cost (snapshot and submission), the drain to disk, the
     * health-view status pass, the retained heap and queue saturation are reported separately, because they share no
     * cause and a single number would hide which one moved.
     */
    record Scenario(
            String record,
            long owners,
            String dirty,
            int samples,
            long snapshotP50Nanos,
            long snapshotP95Nanos,
            long drainP50Nanos,
            long drainP95Nanos,
            long statusP50Nanos,
            long statusP95Nanos,
            long statusBytes,
            long acceptedWrites,
            long queueFull,
            long filesWritten,
            long retainedHeapBytes) {}

    static List<Scenario> runScenarios(List<Long> populations, int samples) throws IOException {
        List<Scenario> out = new ArrayList<>();
        for (long owners : populations) {
            for (String record : List.of("small", "large")) {
                for (String dirty : List.of("idle", "few", "all")) {
                    out.add(measureScenario(owners, record, dirty, samples));
                }
            }
        }
        writeScenarioReports(out);
        return out;
    }

    private static Scenario measureScenario(long owners, String record, String dirty, int samples) throws IOException {
        List<String> base = record.equals("large") ? largeRecord() : smallRecord();
        int count = Math.toIntExact(owners);
        List<UUID> ids =
                IntStream.range(0, count).mapToObj(index -> new UUID(1, index)).toList();
        Path workDir = Files.createTempDirectory("friendai-bench-scenario-");
        OwnerStoreWriter writer = new OwnerStoreWriter();
        List<Long> snapshots = new ArrayList<>();
        List<Long> drains = new ArrayList<>();
        List<Long> statusPasses = new ArrayList<>();
        long statusBytes = -1;
        long accepted;
        long queueFull;
        long filesWritten;
        long retainedHeapBytes;
        try (OwnerFileStore<List<String>> store =
                new OwnerFileStore<>(workDir, CODEC, writer, OwnerFileStore.WriterOwnership.BORROWED)) {
            for (UUID id : ids) store.book(id).addAll(base);
            // Warm pass: class loading, first file creation and the page cache are not part of any sample. It also
            // settles the population, because a bounded writer queue cannot take a full large population in one pass
            // and the leftover dirty owners would otherwise leak into the "idle" samples.
            for (UUID id : ids) store.book(id).add("warm");
            settle(store, writer);
            for (int sample = 0; sample < samples; sample++) {
                int touched = switch (dirty) {
                    case "idle" -> 0;
                    case "few" -> Math.max(1, count / 100);
                    default -> count;
                };
                for (int index = 0; index < touched; index++) {
                    store.book(ids.get(index)).add("sample-" + sample);
                }
                long started = System.nanoTime();
                store.flushAll();
                long submitted = System.nanoTime();
                awaitDrain(writer);
                long drained = System.nanoTime();
                long allocationBefore = allocatedBytes();
                store.statuses();
                long allocationAfter = allocatedBytes();
                long measured = System.nanoTime();
                snapshots.add(submitted - started);
                drains.add(drained - started);
                statusPasses.add(measured - drained);
                if (allocationBefore >= 0 && allocationAfter >= 0) statusBytes = allocationAfter - allocationBefore;
            }
            var finalStatuses = store.statuses();
            accepted = finalStatuses.values().stream()
                    .filter(status -> !status.dirty() && status.failure() == null)
                    .count();
            queueFull = finalStatuses.values().stream()
                    .filter(status ->
                            status.failure() == dev.friendai.foundation.persistence.StoreFailureKind.QUEUE_FULL)
                    .count();
            filesWritten = countFiles(workDir);
            retainedHeapBytes = usedHeapBytes();
        } finally {
            writer.close();
            deleteTree(workDir);
        }
        snapshots.sort(Long::compare);
        drains.sort(Long::compare);
        statusPasses.sort(Long::compare);
        return new Scenario(
                record,
                owners,
                dirty,
                samples,
                percentile(snapshots, 50),
                percentile(snapshots, 95),
                percentile(drains, 50),
                percentile(drains, 95),
                percentile(statusPasses, 50),
                percentile(statusPasses, 95),
                statusBytes,
                accepted,
                queueFull,
                filesWritten,
                retainedHeapBytes);
    }

    private static List<String> smallRecord() {
        List<String> facts = new ArrayList<>();
        for (int index = 0; index < 8; index++) facts.add("fact-" + index + ": " + "x".repeat(24));
        return facts;
    }

    private static List<String> largeRecord() {
        List<String> facts = new ArrayList<>();
        for (int index = 0; index < 200; index++) facts.add("fact-" + index + ": " + "y".repeat(32));
        return facts;
    }

    /** Resolves when every write submitted before this call has completed on the production single-thread writer. */
    private static void awaitDrain(OwnerStoreWriter writer) {
        java.util.concurrent.CountDownLatch barrier = new java.util.concurrent.CountDownLatch(1);
        while (!writer.trySubmit(barrier::countDown)) {
            Thread.onSpinWait();
        }
        try {
            barrier.await(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Repeats the flush/drain cycle until no owner is left dirty. The writer's queue is bounded on purpose, so a
     * population larger than it takes several passes; each pass is the production retry path for {@code QUEUE_FULL}.
     */
    private static void settle(OwnerFileStore<List<String>> store, OwnerStoreWriter writer) {
        for (int pass = 0; pass < 1_000; pass++) {
            store.flushAll();
            awaitDrain(writer);
            if (store.statuses().values().stream().noneMatch(status -> status.dirty())) return;
            if (store.statuses().values().stream()
                    .noneMatch(status ->
                            status.failure() == dev.friendai.foundation.persistence.StoreFailureKind.QUEUE_FULL))
                return;
        }
    }

    /** This thread's allocated bytes, or -1 where the JVM does not expose the counter. */
    static long allocatedBytes() {
        var bean = java.lang.management.ManagementFactory.getThreadMXBean();
        if (bean instanceof com.sun.management.ThreadMXBean sun && sun.isThreadAllocatedMemorySupported()) {
            return sun.getCurrentThreadAllocatedBytes();
        }
        return -1;
    }

    private static void writeScenarioReports(List<Scenario> measured) throws IOException {
        Files.createDirectories(OUTPUT_DIR);
        List<Scenario> scenarios = new ArrayList<>(readScenarios());
        scenarios.removeIf(existing -> measured.stream()
                .anyMatch(row -> row.owners() == existing.owners()
                        && row.record().equals(existing.record())
                        && row.dirty().equals(existing.dirty())));
        scenarios.addAll(measured);
        scenarios.sort(Comparator.comparingLong(Scenario::owners)
                .thenComparing(Scenario::record)
                .thenComparing(Scenario::dirty));
        JsonArray rows = new JsonArray();
        for (Scenario row : scenarios) {
            JsonObject object = new JsonObject();
            object.addProperty("record", row.record());
            object.addProperty("owners", row.owners());
            object.addProperty("dirty", row.dirty());
            object.addProperty("samples", row.samples());
            object.addProperty("snapshotP50Nanos", row.snapshotP50Nanos());
            object.addProperty("snapshotP95Nanos", row.snapshotP95Nanos());
            object.addProperty("drainP50Nanos", row.drainP50Nanos());
            object.addProperty("drainP95Nanos", row.drainP95Nanos());
            object.addProperty("statusP50Nanos", row.statusP50Nanos());
            object.addProperty("statusP95Nanos", row.statusP95Nanos());
            object.addProperty("statusBytes", row.statusBytes());
            object.addProperty("acceptedWrites", row.acceptedWrites());
            object.addProperty("queueFull", row.queueFull());
            object.addProperty("filesWritten", row.filesWritten());
            object.addProperty("retainedHeapBytes", row.retainedHeapBytes());
            rows.add(object);
        }
        Files.writeString(SCENARIO_JSON, rows.toString(), StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder();
        out.append("| record | owners | dirty | samples | snapshot p50/p95 (ms) | drain p50/p95 (ms) "
                + "| status p50/p95 (ms) | status bytes | accepted | queue-full | files | heap (MiB) |\n");
        out.append("|---|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (Scenario row : scenarios) {
            out.append(String.format(
                    Locale.ROOT,
                    "| %s | %d | %s | %d | %.3f / %.3f | %.3f / %.3f | %.3f / %.3f | %d | %d | %d | %d | %.1f |%n",
                    row.record(),
                    row.owners(),
                    row.dirty(),
                    row.samples(),
                    row.snapshotP50Nanos() / 1_000_000.0,
                    row.snapshotP95Nanos() / 1_000_000.0,
                    row.drainP50Nanos() / 1_000_000.0,
                    row.drainP95Nanos() / 1_000_000.0,
                    row.statusP50Nanos() / 1_000_000.0,
                    row.statusP95Nanos() / 1_000_000.0,
                    row.statusBytes(),
                    row.acceptedWrites(),
                    row.queueFull(),
                    row.filesWritten(),
                    row.retainedHeapBytes() / (1024.0 * 1024.0)));
        }
        Files.writeString(SCENARIO_MARKDOWN, out.toString(), StandardCharsets.UTF_8);
    }

    private static List<Scenario> readScenarios() {
        if (!Files.isRegularFile(SCENARIO_JSON)) return new ArrayList<>();
        try {
            List<Scenario> scenarios = new ArrayList<>();
            for (var row :
                    JsonParser.parseString(Files.readString(SCENARIO_JSON)).getAsJsonArray()) {
                JsonObject object = row.getAsJsonObject();
                scenarios.add(new Scenario(
                        object.get("record").getAsString(),
                        object.get("owners").getAsLong(),
                        object.get("dirty").getAsString(),
                        object.get("samples").getAsInt(),
                        object.get("snapshotP50Nanos").getAsLong(),
                        object.get("snapshotP95Nanos").getAsLong(),
                        object.get("drainP50Nanos").getAsLong(),
                        object.get("drainP95Nanos").getAsLong(),
                        object.get("statusP50Nanos").getAsLong(),
                        object.get("statusP95Nanos").getAsLong(),
                        object.get("statusBytes").getAsLong(),
                        object.get("acceptedWrites").getAsLong(),
                        object.get("queueFull").getAsLong(),
                        object.get("filesWritten").getAsLong(),
                        object.get("retainedHeapBytes").getAsLong()));
            }
            return scenarios;
        } catch (IOException | RuntimeException malformed) {
            return new ArrayList<>();
        }
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
