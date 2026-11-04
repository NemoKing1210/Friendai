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
