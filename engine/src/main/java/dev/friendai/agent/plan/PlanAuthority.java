package dev.friendai.agent.plan;

import dev.friendai.execution.queue.Job;
import dev.friendai.execution.queue.JobState;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Who owns a queued plan, decided from the plan's own jobs rather than from the goal board. A plan is owned when
 * the principal it runs as still holds the access every step needs; a plan whose owner was revoked, or whose tool
 * disappeared, is left for activation-time cancellation instead of being replanned.
 */
public final class PlanAuthority {
    private PlanAuthority() {}

    /**
     * The principal a plan runs as: the failed or next pending job's, else any job's, else the owner. A plan without
     * jobs belongs to the owner only.
     */
    public static UUID principal(List<Job> jobs, UUID owner) {
        UUID principal = firstPrincipal(jobs, true);
        if (principal != null) return principal;
        principal = firstPrincipal(jobs, false);
        return principal != null ? principal : owner;
    }

    /**
     * The principal a planner consultation runs as. A troubled plan runs as its own principal, resolved now from its
     * jobs, so a guest's failed plan is never replanned with the owner's tool set and memory. Every other subject runs
     * as the caller the turn already fixed: an owner order is the caller's own work.
     */
    public static UUID consultAs(PlanSubject subject, List<Job> jobs, UUID owner, UUID turnCaller) {
        return subject instanceof PlanSubject.TroubledPlan ? principal(jobs, owner) : turnCaller;
    }

    /** True when the caller may still run every step of the plan; a step whose requirement is unknown is not held. */
    public static boolean holds(List<Job> jobs, Caller caller, Function<String, AccessLevel> required) {
        if (caller == null) return false;
        for (Job job : jobs) {
            AccessLevel min = required.apply(job.tool());
            if (min == null || !caller.access().allows(min)) return false;
        }
        return true;
    }

    private static UUID firstPrincipal(List<Job> jobs, boolean failedOrPendingOnly) {
        for (Job job : jobs) {
            if (failedOrPendingOnly && job.state() != JobState.FAILED && job.state() != JobState.PENDING) continue;
            if (job.principal() != null) return job.principal();
        }
        return null;
    }
}
