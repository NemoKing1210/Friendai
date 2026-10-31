package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.knowledge.ResourceObservation;
import dev.friendai.perception.resource.ObservationScheduler;
import dev.friendai.server.execution.ExecutionCoordinator;
import dev.friendai.task.TaskContext;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;

/**
 * Keeps one line per tick about what a test's companion was doing, and prints the tail of that record when the test
 * stops making progress. A flaky task test otherwise reports only the assertion that lost the race — "ran out of time"
 * or "no stone observed nearby" — which says nothing about the thousand ticks before it; the trace answers "what was it
 * doing all that time" from the per-class server log that CI uploads for a failed shard.
 *
 * <p>Wrap the ticking body instead of passing it straight to {@link GameTestHelper#succeedWhen}:
 *
 * <pre>{@code
 * TaskTrace trace = TaskTrace.of(h, runner, companion);
 * trace.succeedWhen(() -> {
 *     runner.tick();
 *     h.assertTrue(result.status == TaskStatus.SUCCESS, result.summary);
 * });
 * }</pre>
 *
 * <p>A test that keeps failing the same assertion prints the last twelve ticks every six hundred failed ticks, at most
 * twice, so a slow but healthy run stays quiet while a stuck one leaves a timeline of what changed and what did
 * not. A task that fails and leaves the coordinator idle prints the last sixty ticks at that moment, because everything
 * after it is the test waiting out its budget on a result that will not change; an unexpected exception does the same.
 */
public final class TaskTrace {
    /** Ticks kept for a dump; one minute of test time is enough to see a loop. */
    private static final int CAPACITY = 600;

    /** Failed ticks between two printed digests, so a stall leaves a trail without flooding the log. */
    private static final int DIGEST_EVERY = 600;

    /** Digests one test prints; enough to tell a slow start from a stall without flooding the log. */
    private static final int MAX_DIGESTS = 2;

    /** Samples printed for a stall and for an unexpected exception. */
    private static final int STALL_LINES = 12;

    private static final int EXCEPTION_LINES = 60;

    private static final int TEXT_LIMIT = 320;

    private final GameTestHelper h;
    private final ExecutionCoordinator runner;
    private final CompanionEntity companion;
    private final Deque<String> lines = new ArrayDeque<>();

    private int failedTicks;
    private int digests;
    private boolean busyBefore;
    private boolean endedReported;
    private String firstLine;
    private String previousLine;

    private TaskTrace(GameTestHelper h, ExecutionCoordinator runner, CompanionEntity companion) {
        this.h = h;
        this.runner = runner;
        this.companion = companion;
    }

    public static TaskTrace of(GameTestHelper h, ExecutionCoordinator runner, CompanionEntity companion) {
        return new TaskTrace(h, runner, companion);
    }

    /**
     * Runs {@code body} every tick until it stops throwing, recording each tick first. A stall or an unexpected
     * exception prints the recorded tail before it propagates, so the test's own failure stays the reported one.
     */
    public void succeedWhen(Runnable body) {
        h.succeedWhen(() -> {
            try {
                body.run();
                failedTicks = 0;
            } catch (GameTestAssertException failure) {
                // A test that is simply still working fails its "not done yet" assertion every tick, so only a long
                // run prints: the first minutes of a slow test stay silent, and a stuck one leaves a trail.
                if (++failedTicks % DIGEST_EVERY == 0 && digests < MAX_DIGESTS) {
                    digests++;
                    print(failure.getMessage() + " (" + failedTicks + " ticks)", STALL_LINES);
                }
                throw failure;
            } catch (RuntimeException failure) {
                print(failure.toString(), EXCEPTION_LINES);
                throw failure;
            } finally {
                sample();
            }
        });
    }

    /** The last {@code count} recorded ticks, newest last; for a test that reports its own richer failure. */
    public String digest(int count) {
        StringBuilder text = new StringBuilder();
        for (String line : tail(count)) text.append('\n').append(line);
        return text.toString();
    }

    private void sample() {
        try {
            boolean busy = runner.current().isPresent();
            String next = line();
            if (firstLine == null) firstLine = next;
            lines.addLast(next);
            while (lines.size() > CAPACITY) lines.removeFirst();
            // Every step a multi-step test runs ends here, and the step that failed is the one worth reading: the
            // last tick it was running says how it went, and the steps after it are the test's reaction.
            if (busyBefore && !busy && previousLine != null) {
                System.out.println("[friendai-trace] " + companion.getUUID() + " end " + previousLine);
            }
            // A task that failed and left the coordinator idle is the moment worth recording: everything after it is
            // the test waiting out its budget on a result that will not change.
            if (busyBefore && !busy && failedTicks > 0 && !endedReported) {
                endedReported = true;
                print("the task ended while the test still expected success", EXCEPTION_LINES);
            }
            busyBefore = busy;
            previousLine = next;
        } catch (RuntimeException ignored) {
            // A diagnostic must never be the reason a test fails.
        }
    }

    private String line() {
        BlockPos at = companion.blockPosition();
        TaskContext context = runner.activeTaskContext();
        int queued = context.observations().observer(companion).queuedJobs();
        List<ResourceObservation> seen = context.resources().recentVisible(companion, 8);
        String task = runner.current()
                .map(current -> {
                    String status = current.debugStatus();
                    return status.isEmpty() ? current.describe() : current.type() + " " + status;
                })
                .orElse("idle");
        ObservationScheduler.TickMetrics metrics = context.observations().lastMetrics();
        return "t=" + h.getTick()
                + " at=" + at.getX() + "," + at.getY() + "," + at.getZ()
                + " queue=" + runner.pendingJobs()
                + " sight=" + queued
                + " rays=" + metrics.completedRays() + "/" + metrics.scheduledRays()
                + " us=" + metrics.elapsedNanos() / 1_000L
                + " seen=" + seen.size()
                + " " + shorten(task);
    }

    private void print(String reason, int count) {
        System.out.println("[friendai-trace] " + companion.getUUID() + " " + reason);
        if (firstLine != null) System.out.println("[friendai-trace] first " + firstLine);
        for (String line : tail(count)) System.out.println("[friendai-trace] " + line);
    }

    private List<String> tail(int count) {
        int skip = Math.max(0, lines.size() - count);
        return lines.stream().skip(skip).toList();
    }

    private static String shorten(String text) {
        String flat = text.replace('\n', ' ');
        return flat.length() <= TEXT_LIMIT ? flat : flat.substring(0, TEXT_LIMIT - 1) + "…";
    }
}
