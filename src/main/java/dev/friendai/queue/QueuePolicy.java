package dev.friendai.queue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Stateless readiness and selection policy. */
public final class QueuePolicy {
    private static final Comparator<Job> SELECTION =
            Comparator.comparingInt(Job::priority).reversed().thenComparingLong(Job::order);

    private QueuePolicy() {}

    public static boolean ready(JobQueue queue, Job job, GuardFacts facts) {
        return ready(queue, job, facts, statuses(queue));
    }

    private static boolean ready(JobQueue queue, Job job, GuardFacts facts, Map<Long, PlanStatus> statuses) {
        if (job.state() != JobState.PENDING) return false;
        if (job.planId() != 0 && statuses.get(job.planId()) == PlanStatus.NEEDS_DECISION) return false;
        if (job.retryAtTick() > queue.clock()) return false;
        if (!dependenciesDone(queue, job)) return false;
        for (Guard guard : job.guards()) if (!guard.holds(facts)) return false;
        return true;
    }

    public static boolean dependenciesDone(JobQueue queue, Job job) {
        for (long dependency : job.after()) {
            if (queue.job(dependency).filter(j -> j.state() == JobState.DONE).isEmpty()) return false;
        }
        return true;
    }

    public static Optional<Job> next(JobQueue queue, GuardFacts facts) {
        Map<Long, PlanStatus> statuses = statuses(queue);
        Job best = null;
        for (Job job : queue.jobs()) {
            if (!ready(queue, job, facts, statuses)) continue;
            if (best == null || SELECTION.compare(job, best) < 0) best = job;
        }
        return Optional.ofNullable(best);
    }

    public static PlanStatus planStatus(JobQueue queue, long id) {
        List<Job> jobs = queue.jobsOfPlan(id);
        if (jobs.isEmpty()) return queue.plan(id).filter(p -> p.definition() != null && p.cursor() != null)
                .map(p -> p.cursor().stack().isEmpty() ? PlanStatus.FAILED : PlanStatus.ACTIVE).orElse(PlanStatus.FAILED);
        return status(queue, jobs);
    }

    /** The status of every plan, computed in one pass over the jobs. */
    public static Map<Long, PlanStatus> statuses(JobQueue queue) {
        Map<Long, List<Job>> byPlan = new HashMap<>();
        for (Job job : queue.jobs()) if (job.planId() != 0) byPlan.computeIfAbsent(job.planId(), id -> new ArrayList<>()).add(job);
        Map<Long, PlanStatus> result = new HashMap<>();
        byPlan.forEach((id, jobs) -> result.put(id, status(queue, jobs)));
        for (PlanRecord plan : queue.plans()) if (!result.containsKey(plan.id())) {
            result.put(plan.id(), plan.definition() != null && plan.cursor() != null && !plan.cursor().stack().isEmpty()
                    ? PlanStatus.ACTIVE : PlanStatus.FAILED);
        }
        return result;
    }

    private static PlanStatus status(JobQueue queue, List<Job> jobs) {
        if (jobs.isEmpty()) return PlanStatus.FAILED;
        boolean allTerminal = true;
        for (Job job : jobs) {
            if (job.state() == JobState.FAILED && job.onFailure() == FailurePolicy.ASK) return PlanStatus.NEEDS_DECISION;
            if (job.state() == JobState.CANCELLED && Job.MISSING_TOOL_RESULT.equals(job.result())) return PlanStatus.NEEDS_DECISION;
            if (!JobQueue.terminal(job)) allTerminal = false;
        }
        if (allTerminal) {
            return jobs.stream().anyMatch(j -> j.state() == JobState.FAILED || j.state() == JobState.CANCELLED)
                    ? PlanStatus.FAILED : PlanStatus.DONE;
        }
        // A pending job behind a failed, skipped or cancelled step can never run unless somebody repairs the plan.
        for (Job job : jobs) {
            if (job.state() != JobState.PENDING) continue;
            for (long dependency : job.after()) {
                Job other = queue.job(dependency).orElse(null);
                if (other == null || (JobQueue.terminal(other) && other.state() != JobState.DONE)) return PlanStatus.NEEDS_DECISION;
            }
        }
        return PlanStatus.ACTIVE;
    }

    public static String notReadyReason(JobQueue queue, Job job, GuardFacts facts) {
        if (job.planId() != 0 && planStatus(queue, job.planId()) == PlanStatus.NEEDS_DECISION) return "plan waits for your decision";
        if (job.retryAtTick() > queue.clock()) return "retries in " + (job.retryAtTick() - queue.clock()) + " ticks";
        for (long id : job.after()) {
            Job other = queue.job(id).orElse(null);
            if (other == null || other.state() != JobState.DONE) return "depends on #" + id + ", which " + (other == null ? "is missing" : stateWord(other));
        }
        return job.guards().stream().filter(g -> !g.holds(facts)).findFirst().map(g -> "waits for " + g.describe()).orElse("pending");
    }

    private static String stateWord(Job job) {
        return switch (job.state()) {
            case FAILED -> "failed";
            case CANCELLED -> "was cancelled";
            case SKIPPED -> "was skipped";
            default -> job.state().name().toLowerCase();
        };
    }

    public static String summaryReason(JobQueue q, Job j, GuardFacts f) { return notReadyReason(q, j, f).replace("waits for ", "needs "); }

    public static QueueSnapshot snapshot(JobQueue queue, GuardFacts facts) {
        Map<Long, PlanStatus> statuses = statuses(queue);
        List<Job> running = new ArrayList<>(), ready = new ArrayList<>(), waiting = new ArrayList<>();
        for (Job job : queue.jobs()) {
            if (job.state() == JobState.RUNNING) running.add(job);
            else if (job.state() == JobState.PENDING) (ready(queue, job, facts, statuses) ? ready : waiting).add(job);
        }
        ready.sort(SELECTION);
        LinkedHashMap<Long, String> reasons = new LinkedHashMap<>();
        for (Job job : waiting) reasons.put(job.id(), notReadyReason(queue, job, facts));
        return new QueueSnapshot(running, ready, waiting, reasons, queue.archives());
    }
}
