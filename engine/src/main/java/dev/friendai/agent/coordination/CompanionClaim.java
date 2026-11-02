package dev.friendai.agent.coordination;

import dev.friendai.agent.autonomy.WorkConflict;
import dev.friendai.execution.queue.Job;
import dev.friendai.execution.queue.JobQueue;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A read-only claim derived from one live job. It is not persisted and grants no permission: a terminal job claims
 * nothing, and two companions can only compare claims, never execute work for each other.
 */
public record CompanionClaim(UUID owner, long jobId, String tool, String target) {
    public CompanionClaim {
        if (owner == null) throw new IllegalArgumentException("owner");
        if (jobId < 1) throw new IllegalArgumentException("jobId");
        tool = tool == null ? "" : tool;
        target = target == null ? "" : target;
    }

    /** The claim of a live job, or empty for a terminal/missing one. */
    public static Optional<CompanionClaim> of(UUID owner, Job job) {
        if (owner == null || job == null || JobQueue.terminal(job)) {
            return Optional.empty();
        }
        return Optional.of(new CompanionClaim(owner, job.id(), job.tool(), WorkConflict.target(job.args())));
    }

    /** The first live job of {@code other} that repeats this claim's tool and target. */
    public boolean conflictsWith(Job other) {
        return other != null
                && !JobQueue.terminal(other)
                && tool.equals(other.tool())
                && target.equals(WorkConflict.target(other.args()));
    }

    /** A claim in {@code others} that repeats {@code candidate}, or empty. */
    public static Optional<CompanionClaim> duplicate(UUID otherOwner, List<Job> others, Job candidate) {
        if (others == null || others.isEmpty()) return Optional.empty();
        String tool = candidate == null ? "" : candidate.tool();
        String target = candidate == null ? "" : WorkConflict.target(candidate.args());
        for (Job job : others) {
            if (job == null
                    || JobQueue.terminal(job)
                    || !job.tool().equals(tool)
                    || !WorkConflict.target(job.args()).equals(target)) {
                continue;
            }
            return Optional.of(new CompanionClaim(otherOwner, job.id(), job.tool(), target));
        }
        return Optional.empty();
    }
}
