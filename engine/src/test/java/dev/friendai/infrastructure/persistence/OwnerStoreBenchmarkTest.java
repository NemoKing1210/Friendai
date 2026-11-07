package dev.friendai.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OwnerStoreBenchmarkTest {
    private static final Path JSON = Path.of("build/owner-store-benchmark.json");
    private static final Path MARKDOWN = Path.of("build/owner-store-benchmark.md");

    @BeforeEach
    void onlyWithTheBenchmarkFlag() {
        Assumptions.assumeTrue("1".equals(System.getenv("FRIENDAI_BENCHMARK")));
    }

    @Test
    void benchmarkWritesRowsForEveryPopulation() throws Exception {
        Files.deleteIfExists(JSON);
        Files.deleteIfExists(MARKDOWN);

        for (int owners : List.of(8, 64, 1_000, 10_000)) {
            Path dir = Files.createTempDirectory("friendai-bench-");
            try {
                OwnerStoreBenchmark.Report report = OwnerStoreBenchmark.run(owners, dir);

                assertEquals(owners, report.owners());
                assertTrue(report.filesWritten() >= owners, "every owner must reach disk");
                assertTrue(report.flushNanos() > 0, "the batch flush must be timed");
                assertTrue(report.loadNanos() > 0, "the reload pass must be timed");
                assertTrue(report.p95FlushNanos() >= report.p50FlushNanos());
                assertFalse(Files.exists(dir), "the harness must remove its own work tree");
            } finally {
                deleteTree(dir);
            }
        }

        assertTrue(Files.exists(JSON), "the machine-readable report must be written");
        assertTrue(Files.exists(MARKDOWN), "the human table must be written");

        JsonArray rows = JsonParser.parseString(Files.readString(JSON)).getAsJsonArray();
        assertEquals(4, rows.size());
        for (var row : rows) {
            JsonObject object = row.getAsJsonObject();
            assertTrue(object.get("owners").getAsLong() > 0);
            assertTrue(object.get("loadNanos").getAsLong() > 0);
            assertTrue(object.get("flushNanos").getAsLong() > 0);
            assertTrue(object.get("filesWritten").getAsLong() > 0);
        }
    }

    @Test
    void scenariosSeparateSnapshotFromDrainAndReportSaturation() throws Exception {
        List<OwnerStoreBenchmark.Scenario> scenarios = OwnerStoreBenchmark.runScenarios(List.of(8L, 64L, 1_000L), 3);
        assertEquals(18, scenarios.size(), "three populations x two record sizes x three dirty populations");
        for (OwnerStoreBenchmark.Scenario row : scenarios) {
            assertTrue(row.snapshotP95Nanos() >= row.snapshotP50Nanos(), "snapshot p95: " + row);
            assertTrue(row.drainP95Nanos() >= row.drainP50Nanos(), "drain p95: " + row);
            assertTrue(row.drainP95Nanos() >= row.snapshotP50Nanos(), "the drain includes the snapshot: " + row);
            assertTrue(row.statusP50Nanos() > 0, "the status view was timed: " + row);
            assertTrue(row.acceptedWrites() > 0, "the warm pass settled every owner: " + row);
            assertTrue(row.filesWritten() > 0, "the population reached disk: " + row);
        }
        OwnerStoreBenchmark.Scenario saturated = scenarios.stream()
                .filter(row -> row.owners() == 1_000
                        && row.dirty().equals("all")
                        && row.record().equals("small"))
                .findFirst()
                .orElseThrow();
        assertTrue(
                saturated.queueFull() > 0,
                "a fully dirty population cannot fit the bounded writer in one pass: " + saturated);
        assertEquals(
                0,
                scenarios.stream()
                        .filter(row -> row.dirty().equals("idle"))
                        .mapToLong(OwnerStoreBenchmark.Scenario::queueFull)
                        .sum(),
                "an idle population never saturates the writer");
        assertTrue(Files.exists(Path.of("build/owner-store-scenarios.json")));
        assertTrue(Files.exists(Path.of("build/owner-store-scenarios.md")));
    }

    @Test
    void scenariosReportTheTenThousandOwnerTrend() throws Exception {
        List<OwnerStoreBenchmark.Scenario> scenarios = OwnerStoreBenchmark.runScenarios(List.of(10_000L), 2);
        assertEquals(6, scenarios.size());
        for (OwnerStoreBenchmark.Scenario row : scenarios) {
            assertTrue(row.owners() == 10_000);
            assertTrue(row.snapshotP95Nanos() >= row.snapshotP50Nanos());
            assertTrue(row.acceptedWrites() > 0);
            assertTrue(row.retainedHeapBytes() > 0);
            assertTrue(row.statusBytes() != 0, "the status pass allocation is reported or -1");
        }
        assertTrue(Files.exists(Path.of("build/owner-store-scenarios.json")));
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
