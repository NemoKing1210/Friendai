package dev.friendai.execution.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import com.google.gson.JsonObject;
import dev.friendai.foundation.diagnostics.LogContext;
import java.util.List;
import org.junit.jupiter.api.Test;

class JobQueueProvenanceTest {
    private static QueuedStep step(String tool) {
        return new QueuedStep(tool, new JsonObject(), null, List.of(), GuardAction.WAIT, null, 1, List.of());
    }

    private static LogContext context(String trace) {
        return new LogContext(trace, 4L, 0L, 2L, 0L, 7L, 0L, 0L, "mine_block_at", 12L);
    }

    @Test
    void stampingIsACopyAndKeepsTheOriginalUntouched() {
        JobQueue q = new JobQueue(QueueLimits.DEFAULTS);
        Job job = q.addDirect(step("mine_block_at"));

        Job stamped = q.stampProvenance(job.id(), context("t-1"));

        assertNotSame(job, stamped);
        assertEquals(LogContext.NONE, job.diagnostic());
        assertEquals("t-1", stamped.diagnostic().trace());
        assertEquals(7L, stamped.diagnostic().execution());
        assertEquals(2L, stamped.diagnostic().job());
    }

    @Test
    void priorityAndArgumentCopiesKeepTheCapturedInvocation() {
        JobQueue q = new JobQueue(QueueLimits.DEFAULTS);
        Job job = q.addDirect(step("mine_block_at"));
        q.stampProvenance(job.id(), context("t-2"));

        q.setPriority(job.id(), 9);
        assertEquals("t-2", q.job(job.id()).orElseThrow().diagnostic().trace());

        JsonObject args = new JsonObject();
        args.addProperty("count", 3);
        q.updateArgs(job.id(), args);
        Job updated = q.job(job.id()).orElseThrow();
        assertEquals("t-2", updated.diagnostic().trace());
        assertEquals("mine_block_at", updated.diagnostic().toolCall());
        assertEquals(12L, updated.diagnostic().cause());
    }

    @Test
    void mergesKeepTheFirstJobsInvocation() {
        JobQueue q = new JobQueue(QueueLimits.DEFAULTS);
        JobQueue.Added added = q.addPlan("p", List.of(step("mine_blocks"), step("place_block")), PlanOptions.DEFAULT);
        q.stampProvenance(added.jobIds().get(0), context("t-3"));

        q.merge(added.jobIds().get(0), added.jobIds().get(1), new JsonObject());

        assertEquals(
                "t-3", q.job(added.jobIds().get(0)).orElseThrow().diagnostic().trace());
    }

    @Test
    void aPrerequisiteRewriteKeepsTheBlockedJobsInvocation() {
        JobQueue q = new JobQueue(QueueLimits.DEFAULTS);
        Job blocked = q.addDirect(step("place_block"));
        q.stampProvenance(blocked.id(), context("t-4"));

        q.insertPrerequisite(blocked.id(), step("craft"));

        assertEquals("t-4", q.job(blocked.id()).orElseThrow().diagnostic().trace());
        assertEquals(
                LogContext.NONE,
                q.jobs().stream()
                        .filter(job -> job.id() != blocked.id())
                        .findFirst()
                        .orElseThrow()
                        .diagnostic(),
                "the new prerequisite is stamped by the invocation that asked for it, not by this copy");
    }

    @Test
    void stampingAnUnknownJobOrAnEmptyContextChangesNothing() {
        JobQueue q = new JobQueue(QueueLimits.DEFAULTS);
        Job job = q.addDirect(step("mine_block_at"));

        assertEquals(null, q.stampProvenance(99L, context("t-5")));
        assertEquals(
                LogContext.NONE, q.stampProvenance(job.id(), LogContext.NONE).diagnostic());
    }
}
