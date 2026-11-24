package dev.friendai.execution.queue;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Explicit, conservative optimization of adjacent count steps. */
public final class QueueOptimizer {
    public record Merge(long firstId, long secondId, String tool, int count) {}

    private QueueOptimizer() {}

    /**
     * The merges this queue admits, planned from one snapshot of each plan.
     *
     * <p>Only count steps that really neighbour each other can merge: a settled row between them no longer runs, so it
     * does not separate the run, while a running, failed or waiting-for-a-decision row keeps its own boundary. The
     * owner, the guards, the dependency edges and the tool's own count limit are part of the comparison, so a merge
     * never changes who asked, when a step may run or what its task accepts.
     */
    public static List<Merge> suggest(JobQueue queue, Long planId, MergePolicy policy) {
        if (policy == null) return List.of();
        List<Merge> result = new ArrayList<>();
        for (PlanRecord plan : queue.plans()) {
            if (planId != null && plan.id() != planId) continue;
            List<Job> ordered = queue.jobsOfPlan(plan.id()).stream()
                    .sorted(Comparator.comparingLong(Job::order))
                    .toList();
            int index = 0;
            while (index < ordered.size()) {
                Job first = ordered.get(index);
                int count = count(first, policy);
                if (count < 0 || !candidate(first, queue)) {
                    index++;
                    continue;
                }
                int limit = policy.mergeLimit(first.tool());
                int next = index + 1;
                while (next < ordered.size()) {
                    Job second = ordered.get(next);
                    // A settled row no longer runs, so it neither separates the neighbours nor may join a merge.
                    if (settled(second)) {
                        next++;
                        continue;
                    }
                    if (second.state() != JobState.PENDING
                            || queue.jobHeld(second.id())
                            || !companion(first, second, policy)) break;
                    int add = count(second, policy);
                    if (add < 0 || count + add > limit) break;
                    count += add;
                    result.add(new Merge(first.id(), second.id(), first.tool(), count));
                    next++;
                }
                index = next;
            }
        }
        return List.copyOf(result);
    }

    /**
     * Applies every merge the queue admits in one pass, so a plan is scanned once rather than after each merge. A
     * merge that the queue refuses ends that run, because the counts already planned assume the row was removed.
     */
    public static JobQueue.Result apply(JobQueue queue, Long planId, MergePolicy policy) {
        List<String> text = new ArrayList<>();
        Set<Long> stopped = new HashSet<>();
        for (Merge merge : suggest(queue, planId, policy)) {
            if (stopped.contains(merge.firstId())) continue;
            Optional<Job> first = queue.job(merge.firstId());
            if (first.isEmpty()) {
                stopped.add(merge.firstId());
                continue;
            }
            JsonObject args = first.get().args();
            args.addProperty(policy.countArgument(merge.tool()), merge.count());
            if (!queue.merge(merge.firstId(), merge.secondId(), args).ok()) {
                stopped.add(merge.firstId());
                continue;
            }
            text.add("merged #" + merge.firstId() + " and #" + merge.secondId() + " into " + merge.tool() + " count="
                    + merge.count());
        }
        return text.isEmpty() ? JobQueue.Result.ok("nothing to merge") : JobQueue.Result.ok(String.join("; ", text));
    }

    /** Whether a job may start a merged run: pending, unguarded, unheld, with no dependency of its own. */
    private static boolean candidate(Job job, JobQueue queue) {
        return job.state() == JobState.PENDING
                && job.guards().isEmpty()
                && job.after().isEmpty()
                && job.retryAtTick() == 0
                && !queue.jobHeld(job.id());
    }

    /**
     * Whether the job may join a run that started at {@code first}. Identical guards still change meaning when the
     * pair is evaluated once instead of twice, so a guarded step never joins.
     */
    private static boolean companion(Job first, Job second, MergePolicy policy) {
        if (!first.tool().equals(second.tool())
                || !Objects.equals(first.principal(), second.principal())
                || !second.guards().isEmpty()
                || !(second.after().isEmpty() || second.after().equals(List.of(first.id())))
                || first.priority() != second.priority()
                || first.onFailure() != second.onFailure()
                || first.onUnmet() != second.onUnmet()
                || first.maxAttempts() != second.maxAttempts()
                || second.retryAtTick() > 0
                || second.state() != JobState.PENDING) return false;
        String key = policy.countArgument(first.tool());
        return key != null && sameExceptCount(first.args(), second.args(), key);
    }

    private static boolean settled(Job job) {
        return job.state() == JobState.DONE || job.state() == JobState.CANCELLED || job.state() == JobState.SKIPPED;
    }

    private static int count(Job job, MergePolicy policy) {
        String key = policy.countArgument(job.tool());
        if (key == null || !job.args().has(key)) return -1;
        try {
            return job.args().get(key).getAsInt();
        } catch (RuntimeException malformed) {
            return -1;
        }
    }

    private static boolean sameExceptCount(JsonObject first, JsonObject second, String key) {
        JsonObject a = first.deepCopy(), b = second.deepCopy();
        a.remove(key);
        b.remove(key);
        return a.equals(b);
    }
}
