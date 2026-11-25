package dev.friendai.execution.queue;

import java.util.List;
import java.util.Locale;
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
            return JobQueue.terminal(job)
                    ? Optional.of(terminalRefusal(job, 0L))
                    : Optional.of("job #" + jobId + " is not part of a plan");
        }
        PlanRecord plan = queue.plan(job.planId()).orElse(null);
        if (plan == null) return Optional.of("unknown plan #" + job.planId());
        if (plan.cursor() == null) {
            if (JobQueue.terminal(job) && job.state() != JobState.FAILED && job.state() != JobState.CANCELLED) {
                return Optional.of(terminalRefusal(job, plan.id()));
            }
            return Optional.empty();
        }
        if (JobQueue.terminal(job)) return Optional.of(terminalRefusal(job, plan.id()));
        return insideFrame(plan.cursor()) ? Optional.of(frameRefusal(plan.id())) : Optional.empty();
    }

    /**
     * A terminal step cannot be replaced in place: its state is settled and, once the plan has started, the tree
     * cannot be rewritten behind the cursor. The refusal names the action that still works instead of only repeating
     * that the step is over — {@code queue_edit action=retry} for a failed step, {@code skip} for a cancelled one —
     * and says whether a replacement is possible at all.
     */
    private static String terminalRefusal(Job job, long planId) {
        String id = "#" + job.id();
        String state = job.state().name().toLowerCase(Locale.ROOT);
        String inPlan = planId > 0 ? "; plan #" + planId + " has started" : "";
        return switch (job.state()) {
            case FAILED ->
                id + " is already FAILED, so it cannot be replaced in place" + inPlan
                        + ". Use queue_edit action=retry to run it again.";
            case CANCELLED ->
                id + " was CANCELLED, so it cannot be replaced in place" + inPlan
                        + ". Use queue_edit action=skip to move past it, or queue the work again.";
            default ->
                id + " already finished (" + state + "), so there is nothing to replace"
                        + (planId > 0 ? " in plan #" + planId : "")
                        + "; queue the work again if you need it.";
        };
    }

    /**
     * The same rule for a repair that has not chosen a job yet, asked before a planner consultation is spent. It answers
     * whether a proposal for this plan could be applied at all. A whole structured tree that never started is replaced
     * as a tree; otherwise the answer follows {@link #repairTarget}, so the preflight cannot drift from the patch it
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
        if (repairTarget(queue, planId).isPresent()) return Optional.empty();
        for (Job job : jobs) {
            Optional<String> refusal = reject(queue, job.id());
            if (refusal.isPresent()) return refusal;
        }
        return Optional.of("plan #" + planId + " has no step a repair can replace");
    }

    /**
     * The job a repair replaces: a failed step first, because that failure is what blocked the plan and replacing a
     * pending step behind it would leave the same work stuck; only when nothing failed is the first pending step the
     * target. Empty means no step of this plan can be replaced, which is exactly what the preflight refuses.
     */
    public static Optional<Job> repairTarget(JobQueue queue, long planId) {
        if (queue == null) return Optional.empty();
        List<Job> jobs = queue.jobsOfPlan(planId);
        for (Job job : jobs) {
            if (job.state() == JobState.FAILED && reject(queue, job.id()).isEmpty()) return Optional.of(job);
        }
        for (Job job : jobs) {
            if (job.state() == JobState.PENDING && reject(queue, job.id()).isEmpty()) return Optional.of(job);
        }
        return Optional.empty();
    }

    private static boolean insideFrame(PlanCursor cursor) {
        return cursor != null
                && (!cursor.stack().isEmpty() || !cursor.pendingActionPath().isEmpty());
    }

    private static String frameRefusal(long planId) {
        return "plan #" + planId + " is inside an active structured frame; let it stop or cancel it before patching";
    }
}
