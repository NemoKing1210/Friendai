package dev.friendai.agent.autonomy;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.friendai.execution.queue.Job;
import dev.friendai.execution.queue.JobQueue;
import java.util.List;
import java.util.Optional;

/**
 * Refuses autonomous work the queue already holds: the same tool aimed at the same target. A second copy would
 * only repeat what is queued or running, so free time leaves it to the work that is already there. Counts, radii and
 * other amounts are ignored: the target is what matters, not how much of it one step asks for.
 */
public final class WorkConflict {
    /** Arguments that name what a step works on, most specific first. */
    private static final List<String> TARGET_KEYS =
            List.of("name", "place", "item", "block", "target", "entity", "recipe", "generator");

    private WorkConflict() {}

    /**
     * The first candidate step the queue already holds as a live job, described as {@code tool target}, or empty
     * when nothing in the queue is equivalent.
     */
    public static Optional<String> duplicate(List<Job> jobs, List<ReflexStep> steps) {
        if (jobs == null || jobs.isEmpty() || steps == null) {
            return Optional.empty();
        }
        for (ReflexStep step : steps) {
            String target = target(step.args());
            for (Job job : jobs) {
                if (JobQueue.terminal(job) || !job.tool().equals(step.tool())) {
                    continue;
                }
                if (target(job.args()).equals(target)) {
                    return Optional.of(target.isBlank() ? step.tool() : step.tool() + " " + target);
                }
            }
        }
        return Optional.empty();
    }

    /** What a step's arguments point at, or empty when the tool works on nothing nameable. */
    static String target(JsonObject args) {
        if (args == null) {
            return "";
        }
        for (String key : TARGET_KEYS) {
            JsonElement value = args.get(key);
            if (value == null || value.isJsonNull()) {
                continue;
            }
            String text = value.isJsonPrimitive() ? value.getAsString() : value.toString();
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }
}
