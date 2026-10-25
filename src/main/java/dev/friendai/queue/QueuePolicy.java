package dev.friendai.queue;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/** Stateless readiness and selection policy. */
public final class QueuePolicy {
    private QueuePolicy() {}
    public static boolean ready(JobQueue queue, Job job, GuardFacts facts) {
        if (job.state() != JobState.PENDING || (job.planId() != 0 && planStatus(queue, job.planId()) == PlanStatus.NEEDS_DECISION)) return false;
        if (job.retryAtTick() > queue.clock()) return false;
        for (long dependency : job.after()) if (queue.job(dependency).filter(j -> j.state() == JobState.DONE).isEmpty()) return false;
        return job.guards().stream().allMatch(g -> g.holds(facts));
    }
    public static Optional<Job> next(JobQueue queue, GuardFacts facts) {
        return queue.jobs().stream().filter(j -> ready(queue, j, facts))
                .sorted(Comparator.comparingInt(Job::priority).reversed().thenComparingLong(Job::order)).findFirst();
    }
    public static PlanStatus planStatus(JobQueue queue, long id) {
        List<Job> jobs = queue.jobsOfPlan(id);
        if (jobs.isEmpty()) return PlanStatus.FAILED;
        if (jobs.stream().anyMatch(j -> j.state() == JobState.FAILED && j.onFailure() == FailurePolicy.ASK)
                || jobs.stream().anyMatch(j -> j.state() == JobState.CANCELLED && Job.MISSING_TOOL_RESULT.equals(j.result()))) return PlanStatus.NEEDS_DECISION;
        if (jobs.stream().allMatch(JobQueue::terminal)) {
            return jobs.stream().anyMatch(j -> j.state() == JobState.FAILED || j.state() == JobState.CANCELLED)
                    ? PlanStatus.FAILED : PlanStatus.DONE;
        }
        return PlanStatus.ACTIVE;
    }
    public static String notReadyReason(JobQueue queue, Job job, GuardFacts facts) {
        if (job.planId() != 0 && planStatus(queue, job.planId()) == PlanStatus.NEEDS_DECISION) return "plan waits for your decision";
        if (job.retryAtTick() > queue.clock()) return "retries in " + (job.retryAtTick() - queue.clock()) + " ticks";
        for (long id : job.after()) { Job other = queue.job(id).orElse(null); if (other == null || other.state() != JobState.DONE) return "depends on #" + id + ", which " + (other == null ? "is missing" : other.state().name().toLowerCase()); }
        return job.guards().stream().filter(g -> !g.holds(facts)).findFirst().map(g -> "waits for " + g.describe()).orElse("pending");
    }
    public static String summaryReason(JobQueue q, Job j, GuardFacts f) { return notReadyReason(q, j, f).replace("waits for ", "needs "); }
    public static QueueSnapshot snapshot(JobQueue queue, GuardFacts facts) {
        List<Job> running = queue.jobs().stream().filter(j -> j.state() == JobState.RUNNING).toList();
        List<Job> ready = queue.jobs().stream().filter(j -> j.state() == JobState.PENDING && ready(queue, j, facts)).sorted(Comparator.comparingInt(Job::priority).reversed().thenComparingLong(Job::order)).toList();
        List<Job> waiting = queue.jobs().stream().filter(j -> j.state() == JobState.PENDING && !ready(queue, j, facts)).toList();
        LinkedHashMap<Long, String> reasons = new LinkedHashMap<>(); for (Job j : waiting) reasons.put(j.id(), notReadyReason(queue, j, facts));
        return new QueueSnapshot(running, ready, waiting, reasons, queue.archives());
    }
}
