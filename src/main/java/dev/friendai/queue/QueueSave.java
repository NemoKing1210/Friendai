package dev.friendai.queue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Bounded versioned queue serialization; malformed saves are deliberately dropped. */
public final class QueueSave {
    public static final String PREFIX = "queue:v1:";
    private QueueSave() {}
    public record Decoded(JobQueue queue, long activeJobId, String activeTaskSave) {}

    public static String encode(JobQueue queue, String activeTaskSave) {
        JsonObject root = new JsonObject(); root.addProperty("clock", queue.clock());
        JsonArray plans = new JsonArray();
        for (PlanRecord p : queue.plans()) { JsonObject o = new JsonObject(); o.addProperty("id", p.id()); o.addProperty("goal", p.goal()); o.addProperty("priority", p.priority()); o.addProperty("deadline", p.deadlineTick()); o.addProperty("onFailure", p.onFailure().name()); plans.add(o); }
        root.add("plans", plans);
        JsonArray jobs = new JsonArray();
        for (Job j : queue.jobs()) { JsonObject o = new JsonObject(); o.addProperty("id", j.id()); o.addProperty("plan", j.planId()); o.addProperty("tool", j.tool()); o.add("args", j.args()); o.addProperty("priority", j.priority()); o.addProperty("explicit", j.priorityExplicit()); o.addProperty("state", j.state().name()); o.addProperty("onUnmet", j.onUnmet().name()); o.addProperty("onFailure", j.onFailure().name()); o.addProperty("maxAttempts", j.maxAttempts()); o.addProperty("attempts", j.attempts()); o.addProperty("result", j.result()); o.addProperty("retryAt", j.retryAtTick()); JsonArray guards = new JsonArray(); j.guards().forEach(g -> guards.add(g.toJson())); o.add("guards", guards); jobs.add(o); }
        root.add("jobs", jobs); long active = queue.active().map(Job::id).orElse(0L); root.addProperty("active", active); if (activeTaskSave != null && !activeTaskSave.isBlank()) root.addProperty("activeTask", activeTaskSave);
        return PREFIX + root;
    }
    public static Optional<Decoded> decode(String text, QueueLimits limits) {
        if (text == null || !text.startsWith(PREFIX)) return Optional.empty();
        try {
            JsonObject root = JsonParser.parseString(text.substring(PREFIX.length())).getAsJsonObject();
            JobQueue queue = new JobQueue(limits); JsonArray planArray = root.getAsJsonArray("plans"); JsonArray jobArray = root.getAsJsonArray("jobs");
            if (planArray == null || jobArray == null) return Optional.empty();
            for (var planElement : planArray) {
                JsonObject p = planElement.getAsJsonObject(); long id = p.get("id").getAsLong();
                List<QueuedStep> steps = new ArrayList<>();
                for (var jobElement : jobArray) { JsonObject j = jobElement.getAsJsonObject(); if (j.get("plan").getAsLong() != id) continue; steps.add(step(j)); }
                if (steps.isEmpty()) continue;
                queue.addPlan(p.get("goal").getAsString(), steps, new PlanOptions(p.get("priority").getAsInt(), FailurePolicy.valueOf(p.get("onFailure").getAsString()), 0));
            }
            for (var jobElement : jobArray) { JsonObject j = jobElement.getAsJsonObject(); if (j.get("plan").getAsLong() == 0) queue.addDirect(step(j)); }
            long active = root.has("active") ? root.get("active").getAsLong() : 0;
            // Saved ids are stable when rows were written in insertion order; reject a mismatched active id safely.
            if (active != 0 && queue.job(active).isEmpty()) active = 0;
            if (active != 0) queue.markRunning(active);
            return Optional.of(new Decoded(queue, active, root.has("activeTask") ? root.get("activeTask").getAsString() : ""));
        } catch (RuntimeException ignored) { return Optional.empty(); }
    }
    private static QueuedStep step(JsonObject job) {
        List<Guard> guards = new ArrayList<>(); if (job.has("guards")) for (var e : job.getAsJsonArray("guards")) guards.add(Guard.parse(e.getAsJsonObject()).orElseThrow());
        return new QueuedStep(job.get("tool").getAsString(), job.getAsJsonObject("args"), job.has("priority") ? job.get("priority").getAsInt() : null, guards,
                GuardAction.valueOf(job.get("onUnmet").getAsString()), FailurePolicy.valueOf(job.get("onFailure").getAsString()), job.get("maxAttempts").getAsInt(), List.of());
    }
}
