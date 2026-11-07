package dev.friendai.execution.queue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Headless cost benchmark for the queue work a server tick already does: selecting the next job, the per-plan status
 * pass, the collection copies and the material reserve. It reports p50/p95 per operation and allocated bytes per
 * operation, so a change can be judged against a measured baseline instead of an intuition.
 *
 * <p>The queue is representative rather than synthetic-random: a dozen flat plans of a few steps each, one structured
 * plan stopped inside its frame, one plan waiting for a decision and some finished work — the shapes the executor
 * actually holds. This is a test harness, opt-in through {@code FRIENDAI_BENCHMARK=1}.
 */
final class QueueCostBenchmark {
    private static final Path OUTPUT_DIR = Path.of("build");
    private static final Path JSON = OUTPUT_DIR.resolve("queue-cost-benchmark.json");
    private static final Path MARKDOWN = OUTPUT_DIR.resolve("queue-cost-benchmark.md");
    /** One server tick is 50 ms; a queue pass over a few percent of it is worth a second look. */
    private static final double TICK_MILLIS = 50.0;

    private static final int WARMUP_ITERATIONS = 2_000;
    private static final int SAMPLE_ITERATIONS = 500;
    private static final int SAMPLES = 9;

    private QueueCostBenchmark() {}

    record Row(String op, int iterations, int samples, long p50Nanos, long p95Nanos, long bytesPerOp) {}

    static List<Row> run() throws IOException {
        JobQueue queue = representativeQueue();
        List<Long> planIds = queue.plans().stream().map(PlanRecord::id).toList();
        List<QueuedStep> pending = queue.jobs().stream()
                .filter(job -> job.state() == JobState.PENDING)
                .map(job -> new QueuedStep(
                        job.tool(),
                        job.args(),
                        null,
                        job.guards(),
                        job.onUnmet(),
                        job.onFailure(),
                        job.maxAttempts(),
                        List.of()))
                .toList();
        GuardFacts facts = new GuardFacts() {
            @Override
            public int countItems(String idOrTag) {
                return 0;
            }

            @Override
            public int emptySlots() {
                return 18;
            }

            @Override
            public float healthFraction() {
                return 1.0f;
            }

            @Override
            public int ownerDistance() {
                return 2;
            }

            @Override
            public int countBlocks(String idOrTag, int radius) {
                return 0;
            }

            @Override
            public String dimension() {
                return "minecraft:overworld";
            }
        };

        List<Row> rows = new ArrayList<>();
        rows.add(measure("plans()", queue::plans));
        rows.add(measure("jobs()", queue::jobs));
        rows.add(measure("QueuePolicy.next (one idle tick)", () -> QueuePolicy.next(queue, facts)));
        rows.add(measure("QueuePolicy.statuses (full map)", () -> QueuePolicy.statuses(queue)));
        rows.add(measure("planStatus pass (every plan)", () -> {
            for (long id : planIds) QueuePolicy.planStatus(queue, id);
        }));
        rows.add(
                measure("PlanMaterials.needs (pending steps)", () -> PlanMaterials.needs(pending, block -> List.of())));
        writeReports(queue, rows);
        return rows;
    }

    /** The shapes the executor holds: flat plans, one stopped structured plan, one decision, some finished work. */
    private static JobQueue representativeQueue() {
        // A server-sized queue: the engine default of eight plans is the floor, not what a busy owner holds.
        JobQueue queue = new JobQueue(new QueueLimits(256, 32, 32, 8_192, 5));
        String[][] flat = {
            {"obtain_item", "{\"item\":\"minecraft:oak_log\",\"count\":4}"},
            {"craft_item", "{\"item\":\"minecraft:stick\",\"count\":8}"},
            {"mine_blocks", "{\"block\":\"minecraft:stone\",\"count\":16}"},
            {"go_to", "{\"x\":120,\"y\":64,\"z\":-40}"},
            {"smelt_items", "{\"item\":\"minecraft:iron_ore\",\"count\":3}"},
            {"jump", "{}"},
        };
        for (int plan = 0; plan < 12; plan++) {
            List<QueuedStep> steps = new ArrayList<>();
            for (int index = 0; index < 3; index++) {
                String[] tool = flat[(plan + index) % flat.length];
                steps.add(step(tool[0], tool[1], index == 0 ? List.of() : List.of(index)));
            }
            long id = queue.addPlan("plan " + plan, steps, PlanOptions.DEFAULT).planId();
            if (plan % 4 == 0) {
                long first = queue.jobsOfPlan(id).getFirst().id();
                queue.finish(first, "done");
            }
            if (plan % 5 == 0) {
                long second = queue.jobsOfPlan(id).get(1).id();
                queue.fail(second, "no route");
            }
        }
        long structured = queue.addStructuredPlan(
                "structured",
                new PlanDefinition(List.of(
                        new PlanNode.Action(
                                step("craft_item", "{\"item\":\"minecraft:bread\",\"count\":2}", List.of())),
                        new PlanNode.Action(step("jump", "{}", List.of())))),
                PlanOptions.DEFAULT);
        queue.addJob(structured, step("craft_item", "{\"item\":\"minecraft:bread\",\"count\":2}", List.of()));
        return queue;
    }

    private static QueuedStep step(String tool, String args, List<Integer> after) {
        return new QueuedStep(
                tool,
                com.google.gson.JsonParser.parseString(args).getAsJsonObject(),
                null,
                List.of(),
                GuardAction.WAIT,
                null,
                1,
                after);
    }

    private static Row measure(String op, Runnable action) {
        for (int iteration = 0; iteration < WARMUP_ITERATIONS; iteration++) action.run();
        List<Long> perOp = new ArrayList<>();
        long bytes = -1;
        for (int sample = 0; sample < SAMPLES; sample++) {
            long before = allocatedBytes();
            long started = System.nanoTime();
            for (int iteration = 0; iteration < SAMPLE_ITERATIONS; iteration++) action.run();
            long elapsed = System.nanoTime() - started;
            long after = allocatedBytes();
            perOp.add(elapsed / SAMPLE_ITERATIONS);
            if (before >= 0 && after >= 0) bytes = (after - before) / SAMPLE_ITERATIONS;
        }
        perOp.sort(Long::compare);
        return new Row(op, SAMPLE_ITERATIONS, SAMPLES, percentile(perOp, 50), percentile(perOp, 95), bytes);
    }

    private static long percentile(List<Long> sorted, int percentile) {
        if (sorted.isEmpty()) return 0;
        int index = (int) Math.ceil(sorted.size() * percentile / 100.0) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    /** This thread's allocated bytes, or -1 where the JVM does not expose the counter. */
    static long allocatedBytes() {
        var bean = java.lang.management.ManagementFactory.getThreadMXBean();
        if (bean instanceof com.sun.management.ThreadMXBean sun && sun.isThreadAllocatedMemorySupported()) {
            return sun.getCurrentThreadAllocatedBytes();
        }
        return -1;
    }

    private static void writeReports(JobQueue queue, List<Row> rows) throws IOException {
        Files.createDirectories(OUTPUT_DIR);
        JsonArray array = new JsonArray();
        for (Row row : rows) {
            JsonObject object = new JsonObject();
            object.addProperty("op", row.op());
            object.addProperty("iterations", row.iterations());
            object.addProperty("samples", row.samples());
            object.addProperty("p50Nanos", row.p50Nanos());
            object.addProperty("p95Nanos", row.p95Nanos());
            object.addProperty("bytesPerOp", row.bytesPerOp());
            array.add(object);
        }
        Files.writeString(JSON, array.toString(), StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder();
        out.append("# Queue cost\n\n");
        out.append("Representative queue: ")
                .append(queue.plans().size())
                .append(" plans, ")
                .append(queue.jobs().size())
                .append(" jobs; ")
                .append(WARMUP_ITERATIONS)
                .append(" warmup iterations, ")
                .append(SAMPLES)
                .append(" samples of ")
                .append(SAMPLE_ITERATIONS)
                .append(" iterations each.\n\n");
        out.append("| operation | p50 (µs) | p95 (µs) | bytes/op |\n|---|---:|---:|---:|\n");
        for (Row row : rows) {
            out.append(String.format(
                    Locale.ROOT,
                    "| %s | %.3f | %.3f | %d |%n",
                    row.op(),
                    row.p50Nanos() / 1_000.0,
                    row.p95Nanos() / 1_000.0,
                    row.bytesPerOp()));
        }
        double idleTickMicros = rows.stream()
                .filter(row -> row.op().startsWith("QueuePolicy.next")
                        || row.op().startsWith("planStatus")
                        || row.op().startsWith("PlanMaterials"))
                .mapToDouble(row -> row.p95Nanos() / 1_000.0)
                .sum();
        out.append(String.format(
                Locale.ROOT,
                "%nOne idle tick's queue work (selection + plan statuses + material reserve) is %.3f µs at p95, "
                        + "%.4f%% of a %.0f ms tick.%n",
                idleTickMicros,
                idleTickMicros / (TICK_MILLIS * 1_000.0) * 100.0,
                TICK_MILLIS));
        Files.writeString(MARKDOWN, out.toString(), StandardCharsets.UTF_8);
    }
}
