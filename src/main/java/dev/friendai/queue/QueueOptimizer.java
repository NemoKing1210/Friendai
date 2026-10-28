package dev.friendai.queue;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Explicit, conservative optimization of adjacent count steps. */
public final class QueueOptimizer {
    public record Merge(long firstId, long secondId, String tool, int count) {}

    private QueueOptimizer() {}

    public static List<Merge> suggest(JobQueue queue, Long planId, MergePolicy policy) {
        List<Merge> result = new ArrayList<>();
        for (PlanRecord plan : queue.plans()) {
            if (planId != null && plan.id() != planId) continue;
            List<Job> jobs = queue.jobsOfPlan(plan.id()).stream()
                    .filter(j -> j.state() == JobState.PENDING)
                    .sorted(Comparator.comparingLong(Job::order))
                    .toList();
            for (int i = 1; i < jobs.size(); i++) {
                Job first = jobs.get(i - 1), second = jobs.get(i);
                String key = policy.countArgument(first.tool());
                if (key == null
                        || !first.tool().equals(second.tool())
                        || !first.guards().equals(second.guards())
                        || !first.after().isEmpty()
                        || !second.after().isEmpty()
                        || !sameExceptCount(first.args(), second.args(), key)) continue;
                if (first.priority() != second.priority()
                        || first.onFailure() != second.onFailure()
                        || first.onUnmet() != second.onUnmet()
                        || first.maxAttempts() != second.maxAttempts()
                        || first.retryAtTick() > 0
                        || second.retryAtTick() > 0) continue;
                try {
                    int count = first.args().get(key).getAsInt()
                            + second.args().get(key).getAsInt();
                    if (count <= 64) result.add(new Merge(first.id(), second.id(), first.tool(), count));
                } catch (RuntimeException ignored) {
                }
            }
        }
        return List.copyOf(result);
    }

    private static boolean sameExceptCount(JsonObject first, JsonObject second, String key) {
        JsonObject a = first.deepCopy(), b = second.deepCopy();
        a.remove(key);
        b.remove(key);
        return a.equals(b);
    }

    public static JobQueue.Result apply(JobQueue queue, Long planId, MergePolicy policy) {
        List<String> text = new ArrayList<>();
        while (true) {
            List<Merge> merges = suggest(queue, planId, policy);
            if (merges.isEmpty()) break;
            Merge merge = merges.getFirst();
            Job first = queue.job(merge.firstId()).orElseThrow();
            JsonObject args = first.args();
            args.addProperty(policy.countArgument(merge.tool()), merge.count());
            queue.merge(merge.firstId(), merge.secondId(), args);
            text.add("merged #" + merge.firstId() + " and #" + merge.secondId() + " into " + merge.tool() + " count="
                    + merge.count());
        }
        return text.isEmpty() ? JobQueue.Result.ok("nothing to merge") : JobQueue.Result.ok(String.join("; ", text));
    }
}
