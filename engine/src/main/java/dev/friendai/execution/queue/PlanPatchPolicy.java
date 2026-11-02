package dev.friendai.execution.queue;

import java.util.Optional;

/**
 * Safety rule for editing a queued job. A flat plan's pending step can be replaced by the existing queue edit; a
 * structured plan whose interpreter cursor already holds a frame must not be rewritten behind the cursor, because the
 * path, branch selection and repeat counters would no longer describe the edited tree.
 */
public final class PlanPatchPolicy {
    private PlanPatchPolicy() {}

    public static Optional<String> reject(JobQueue queue, long jobId) {
        if (queue == null) return Optional.of("queue is unavailable");
        Job job = queue.job(jobId).orElse(null);
        if (job == null) return Optional.of("unknown job #" + jobId);
        if (JobQueue.terminal(job)) return Optional.of("#" + jobId + " is already terminal");
        if (job.planId() == 0) return Optional.empty();
        PlanRecord plan = queue.plan(job.planId()).orElse(null);
        if (plan == null || plan.definition() == null || plan.cursor() == null) return Optional.empty();
        PlanCursor cursor = plan.cursor();
        if (!cursor.stack().isEmpty() || !cursor.pendingActionPath().isEmpty()) {
            return Optional.of("plan #" + plan.id()
                    + " is inside an active structured frame; let it stop or cancel it before patching");
        }
        return Optional.empty();
    }
}
