package dev.friendai.execution.queue;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Deterministic choice of one idle prerequisite repair from the current queue. It reads only live jobs and known
 * facts; it never inserts anything itself. A missing carried item guarded by has_item is the first supported repair.
 * A terminal job, a running job, a plan that already holds the same remedy, or a plan without repair consent is not
 * selected.
 */
public final class IdleRepairPolicy {
    /** The only guard this first bounded policy repairs. */
    public static final String REMEDY_TOOL = "obtain_item";

    public record Repair(Job blocked, PlanRepair.Prerequisite prerequisite, String remedyTool, String reason) {}

    private IdleRepairPolicy() {}

    public static Optional<Repair> select(
            List<Job> jobs, GuardFacts facts, Set<String> availableTools, Set<Long> repairConsentPlans) {
        return select(jobs, facts, availableTools, repairConsentPlans, null);
    }

    public static Optional<Repair> select(
            List<Job> jobs,
            GuardFacts facts,
            Set<String> availableTools,
            Set<Long> repairConsentPlans,
            ToolRepairRule tools) {
        if (jobs == null || facts == null || availableTools == null || !availableTools.contains(REMEDY_TOOL)) {
            return Optional.empty();
        }
        List<Job> ordered = jobs.stream()
                .filter(job -> job.state() == JobState.PENDING)
                .filter(job -> job.planId() == 0 || repairConsentPlans.contains(job.planId()))
                .sorted(Comparator.comparingInt(Job::priority)
                        .reversed()
                        .thenComparingLong(Job::order)
                        .thenComparingLong(Job::id))
                .toList();
        for (Job job : ordered) {
            for (Guard guard : job.guards()) {
                if (guard instanceof Guard.HasItem item && !item.holds(facts) && !alreadyRepairing(jobs, item.item())) {
                    return Optional.of(new Repair(
                            job,
                            new PlanRepair.Item(item.item(), item.count(), REMEDY_TOOL),
                            REMEDY_TOOL,
                            "job #" + job.id() + " needs " + item.count() + " " + item.item()));
                }
                if (guard instanceof Guard.ToolAtLeast tool && !tool.holds(facts) && tools != null) {
                    String item = tools.itemFor(tool.block()).orElse(null);
                    if (item != null && !alreadyRepairing(jobs, item)) {
                        return Optional.of(new Repair(
                                job,
                                new PlanRepair.Item(item, 1, REMEDY_TOOL),
                                REMEDY_TOOL,
                                "job #" + job.id() + " needs a tool for " + tool.block()));
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static boolean alreadyRepairing(List<Job> jobs, String item) {
        return jobs.stream()
                .filter(job -> !JobQueue.terminal(job))
                .anyMatch(job -> job.tool().equals(REMEDY_TOOL)
                        && job.args().has("item")
                        && item.equals(job.args().get("item").getAsString()));
    }
}
