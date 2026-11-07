package dev.friendai.agent.autonomy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.friendai.execution.queue.Job;
import dev.friendai.execution.queue.JobQueue;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

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
            for (Job job : jobs) {
                if (JobQueue.terminal(job) || !job.tool().equals(step.tool())) {
                    continue;
                }
                if (sameTarget(job.args(), step.args())) {
                    String target = target(job.args());
                    return Optional.of(target.isBlank() ? step.tool() : step.tool() + " " + target);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * A step the owner's queue does not hold but another companion's queue does. Owners never share a queue, so this
     * is the cross-companion half of the duplicate guard: the same tree, place or build is not worked twice.
     */
    public static Optional<String> duplicateAcross(List<Job> own, List<List<Job>> otherQueues, List<ReflexStep> steps) {
        Optional<String> ownHeld = duplicate(own, steps);
        if (ownHeld.isPresent()) {
            return ownHeld;
        }
        List<ReflexStep> namedTargets =
                steps.stream().filter(step -> !target(step.args()).isBlank()).toList();
        if (namedTargets.isEmpty()) {
            return Optional.empty();
        }
        for (List<Job> queue : otherQueues) {
            Optional<String> held = duplicate(queue, namedTargets);
            if (held.isPresent()) {
                return held;
            }
        }
        return Optional.empty();
    }

    /** What a step's arguments point at, or empty when the tool works on nothing nameable. */
    public static String target(JsonObject args) {
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
        JsonElement batch = args.get("items");
        if (batch != null && batch.isJsonArray()) {
            return String.join(", ", itemIds(batch.getAsJsonArray()));
        }
        return "";
    }

    /**
     * Two steps of the same tool work on the same target. A requested item set is a set: a batch request collides with
     * another request when the two share at least one item id, so an unrelated item is still free work. Every other
     * tool keeps the single-target comparison it had.
     */
    private static boolean sameTarget(JsonObject left, JsonObject right) {
        Set<String> leftItems = requestedItems(left);
        Set<String> rightItems = requestedItems(right);
        if (!leftItems.isEmpty() && !rightItems.isEmpty()) {
            return !Collections.disjoint(leftItems, rightItems);
        }
        return target(left).equals(target(right));
    }

    /**
     * The item ids a step requests through the single-item or the batch form, or empty when it names neither. A
     * higher-precedence target key keeps its existing single-target identity.
     */
    private static Set<String> requestedItems(JsonObject args) {
        if (args == null) {
            return Set.of();
        }
        JsonElement batch = args.get("items");
        JsonElement single = args.get("item");
        boolean hasBatch = batch != null && batch.isJsonArray();
        boolean hasSingle = single != null
                && single.isJsonPrimitive()
                && !single.getAsString().isBlank();
        if (!hasBatch && !hasSingle) {
            return Set.of();
        }
        for (String key : TARGET_KEYS) {
            if (key.equals("item")) {
                break;
            }
            JsonElement value = args.get(key);
            if (value != null
                    && !value.isJsonNull()
                    && !(value.isJsonPrimitive() && value.getAsString().isBlank())) {
                return Set.of();
            }
        }
        Set<String> ids = new TreeSet<>();
        if (hasSingle) {
            ids.add(single.getAsString());
        }
        if (hasBatch) {
            ids.addAll(itemIds(batch.getAsJsonArray()));
        }
        return ids;
    }

    private static Set<String> itemIds(JsonArray items) {
        Set<String> ids = new TreeSet<>();
        for (JsonElement entry : items) {
            if (entry == null || !entry.isJsonObject()) {
                continue;
            }
            JsonElement item = entry.getAsJsonObject().get("item");
            if (item != null && item.isJsonPrimitive() && !item.getAsString().isBlank()) {
                ids.add(item.getAsString());
            }
        }
        return ids;
    }
}
