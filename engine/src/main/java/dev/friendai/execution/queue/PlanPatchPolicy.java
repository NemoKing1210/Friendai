package dev.friendai.execution.queue;

import java.util.List;
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
        if (job.state() == JobState.RUNNING) {
            return Optional.of("job #" + jobId + " is running; let it finish before patching");
        }
        if (job.planId() == 0) {
            return Optional.of(
                    JobQueue.terminal(job)
                            ? "#" + jobId + " is already terminal"
                            : "job #" + jobId + " is not part of a plan");
        }
        PlanRecord plan = queue.plan(job.planId()).orElse(null);
        if (plan == null) return Optional.of("unknown plan #" + job.planId());
        if (plan.cursor() == null) {
            if (JobQueue.terminal(job) && job.state() != JobState.FAILED && job.state() != JobState.CANCELLED) {
                return Optional.of("#" + jobId + " is already terminal");
            }
            return Optional.empty();
        }
        if (JobQueue.terminal(job)) return Optional.of("#" + jobId + " is already terminal");
        return insideFrame(plan.cursor()) ? Optional.of(frameRefusal(plan.id())) : Optional.empty();
    }

    /**
     * The same rule for a repair that has not chosen a job yet, asked before a planner consultation is spent. It answers
     * whether a proposal for this plan could be applied at all. A whole structured tree that never started is replaced
     * as a tree; otherwise the answer is exactly the per-job rule, so the preflight cannot drift from the patch it
     * guards.
     */
    public static Optional<String> rejectPlan(JobQueue queue, long planId) {
        if (queue == null) return Optional.of("queue is unavailable");
        PlanRecord plan = queue.plan(planId).orElse(null);
        if (plan == null) return Optional.of("unknown plan #" + planId);
        List<Job> jobs = queue.jobsOfPlan(planId);
        if (jobs.isEmpty()) {
            // Nothing has started: the whole tree is replaced, which is the one path that works without a job.
            return plan.definition() != null
                    ? Optional.empty()
                    : Optional.of("plan #" + planId + " has no step to repair");
        }
        Optional<String> refusal = Optional.empty();
        for (Job job : jobs) {
            Optional<String> current = reject(queue, job.id());
            if (current.isEmpty()) return Optional.empty();
            if (refusal.isEmpty()) refusal = current;
        }
        return refusal;
    }

    private static boolean insideFrame(PlanCursor cursor) {
        return cursor != null
                && (!cursor.stack().isEmpty() || !cursor.pendingActionPath().isEmpty());
    }

    private static String frameRefusal(long planId) {
        return "plan #" + planId + " is inside an active structured frame; let it stop or cancel it before patching";
    }
}
