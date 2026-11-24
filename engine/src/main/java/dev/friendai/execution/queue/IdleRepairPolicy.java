package dev.friendai.execution.queue;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Deterministic choice of one idle prerequisite repair from the current queue. It reads only live jobs and known
 * facts; it never inserts anything itself. A missing carried item guarded by has_item is the first supported repair.
 * A terminal job, a running job, a plan that already holds the same remedy, or a plan without repair consent is not
 * selected. Neither is a job the queue would not dispatch even with its guard met: one under a hold, one waiting out a
 * retry delay, one whose dependencies are still undecided, or one in a plan that waits for the owner. Repairing those
 * would start gathering for a step nobody asked to unblock yet. Only the guard that actually stops the job is
 * repaired, so a second unmet guard keeps the step waiting instead of triggering work that could not release it.
 */
public final class IdleRepairPolicy {
    /** The only guard this first bounded policy repairs. */
    public static final String REMEDY_TOOL = "obtain_item";

    public record Repair(Job blocked, PlanRepair.Prerequisite prerequisite, String remedyTool, String reason) {}

    private IdleRepairPolicy() {}

    public static Optional<Repair> select(
            JobQueue queue, GuardFacts facts, Set<String> availableTools, Set<Long> repairConsentPlans) {
        return select(queue, facts, availableTools, repairConsentPlans, null);
    }

    public static Optional<Repair> select(
            JobQueue queue,
            GuardFacts facts,
            Set<String> availableTools,
            Set<Long> repairConsentPlans,
            ToolRepairRule tools) {
        return select(queue, facts, availableTools, repairConsentPlans, tools, repair -> true);
    }

    /**
     * As above, but a candidate the caller will not admit (a requester who may no longer use the remedy tool, for
     * example) does not hide the next eligible repair: the first admitted one is returned.
     */
    public static Optional<Repair> select(
            JobQueue queue,
            GuardFacts facts,
            Set<String> availableTools,
            Set<Long> repairConsentPlans,
            ToolRepairRule tools,
            Predicate<Repair> admissible) {
        if (queue == null || facts == null || availableTools == null || !availableTools.contains(REMEDY_TOOL)) {
            return Optional.empty();
        }
        Predicate<Repair> allowed = admissible == null ? repair -> true : admissible;
        List<Job> jobs = queue.jobs();
        List<Job> ordered = jobs.stream()
                .filter(job -> job.state() == JobState.PENDING)
                .filter(job -> job.planId() == 0 || repairConsentPlans.contains(job.planId()))
                // A job already waiting on a queued prerequisite is being serviced; a second remedy would duplicate
                // it, and a settled one that failed is a named blocker for the owner, not a reason to acquire again.
                .filter(job -> job.after().isEmpty())
                .filter(job -> eligible(queue, job, facts))
                .sorted(Comparator.comparingInt(Job::priority)
                        .reversed()
                        .thenComparingLong(Job::order)
                        .thenComparingLong(Job::id))
                .toList();
        for (Job job : ordered) {
            Repair repair = repairFor(queue, job, jobs, facts, tools);
            if (repair != null && allowed.test(repair)) return Optional.of(repair);
        }
        return Optional.empty();
    }

    private static Repair repairFor(JobQueue queue, Job job, List<Job> jobs, GuardFacts facts, ToolRepairRule tools) {
        long startTick = QueuePolicy.progressStartTick(queue, job),
                startDistance = QueuePolicy.progressStartDistance(queue, job);
        for (Guard guard : job.guards()) {
            if (guard instanceof Guard.HasItem item
                    && item.evaluate(facts) == Guard.Truth.FALSE
                    && currentBlocker(job, guard, facts, startTick, startDistance)
                    && !alreadyRepairing(jobs, item.item())) {
                return new Repair(
                        job,
                        new PlanRepair.Item(item.item(), item.count(), REMEDY_TOOL),
                        REMEDY_TOOL,
                        "job #" + job.id() + " needs " + item.count() + " " + item.item());
            }
            if (guard instanceof Guard.ToolAtLeast tool
                    && tool.evaluate(facts) == Guard.Truth.FALSE
                    && tools != null
                    && currentBlocker(job, guard, facts, startTick, startDistance)) {
                String item = tools.itemFor(tool.block()).orElse(null);
                if (item != null && !alreadyRepairing(jobs, item)) {
                    return new Repair(
                            job,
                            new PlanRepair.Item(item, 1, REMEDY_TOOL),
                            REMEDY_TOOL,
                            "job #" + job.id() + " needs a tool for " + tool.block());
                }
            }
        }
        return null;
    }

    /**
     * Whether the queue would dispatch this job if its guard held. A hold, a retry delay, an undecided dependency or a
     * plan waiting for the owner each means the step is not being held back by the fact a remedy would supply.
     */
    private static boolean eligible(JobQueue queue, Job job, GuardFacts facts) {
        if (QueuePolicy.blockingHold(queue, job, facts).isPresent()) return false;
        if (job.retryAtTick() > queue.clock()) return false;
        if (!QueuePolicy.dependenciesDone(queue, job)) return false;
        return job.planId() == 0 || QueuePolicy.planStatus(queue, job.planId()) == PlanStatus.ACTIVE;
    }

    /** Whether every other guard already holds, so this one is the fact that actually stops the job. */
    private static boolean currentBlocker(
            Job job, Guard candidate, GuardFacts facts, long startTick, long startDistance) {
        for (Guard guard : job.guards()) {
            if (guard == candidate) continue;
            if (guard.evaluate(facts, startTick, startDistance) != Guard.Truth.TRUE) return false;
        }
        return true;
    }

    private static boolean alreadyRepairing(List<Job> jobs, String item) {
        return jobs.stream()
                .filter(job -> !JobQueue.terminal(job))
                .anyMatch(job -> job.tool().equals(REMEDY_TOOL)
                        && job.args().has("item")
                        && item.equals(job.args().get("item").getAsString()));
    }
}
