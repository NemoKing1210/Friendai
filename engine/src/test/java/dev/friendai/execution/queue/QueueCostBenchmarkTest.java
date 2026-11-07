package dev.friendai.execution.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QueueCostBenchmarkTest {
    private static final Path JSON = Path.of("build/queue-cost-benchmark.json");
    private static final Path MARKDOWN = Path.of("build/queue-cost-benchmark.md");

    @BeforeEach
    void onlyWithTheBenchmarkFlag() {
        Assumptions.assumeTrue("1".equals(System.getenv("FRIENDAI_BENCHMARK")));
    }

    @Test
    void everyQueuePassIsMeasuredAndReported() throws Exception {
        Files.deleteIfExists(JSON);
        Files.deleteIfExists(MARKDOWN);

        List<QueueCostBenchmark.Row> rows = QueueCostBenchmark.run();

        assertEquals(
                List.of(
                        "plans()",
                        "jobs()",
                        "QueuePolicy.next (one idle tick)",
                        "QueuePolicy.statuses (full map)",
                        "planStatus pass (every plan)",
                        "PlanMaterials.needs (pending steps)"),
                rows.stream().map(QueueCostBenchmark.Row::op).toList());
        for (QueueCostBenchmark.Row row : rows) {
            assertTrue(row.p50Nanos() > 0, "a timed operation reports a real cost: " + row);
            assertTrue(row.p95Nanos() >= row.p50Nanos(), "p95 is at least p50: " + row);
            assertTrue(row.iterations() > 0 && row.samples() > 1);
        }
        assertTrue(Files.exists(JSON), "the machine-readable report must exist");
        assertTrue(Files.readString(MARKDOWN).contains("One idle tick's queue work"));
    }
}
