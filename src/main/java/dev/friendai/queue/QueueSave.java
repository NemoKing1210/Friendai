package dev.friendai.queue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Bounded versioned queue serialization; malformed saves are deliberately dropped. */
public final class QueueSave {
    public static final String PREFIX = "queue:v1:";
    public static final int MAX_SAVE_CHARS = 1_000_000;
    public static final int MAX_FIELD_CHARS = 8_192;
    private QueueSave() {}
    public record Decoded(JobQueue queue, long activeJobId, String activeTaskSave) {}

    public static String encode(JobQueue queue, String activeTaskSave) {
        JsonObject root = new JsonObject();
        root.addProperty("clock", queue.clock()); root.addProperty("nextJob", queue.nextJobId());
        root.addProperty("nextPlan", queue.nextPlanId()); root.addProperty("nextOrder", queue.nextOrder());
        JsonArray plans = new JsonArray();
        for (PlanRecord p : queue.plans()) {
            JsonObject o = new JsonObject(); o.addProperty("id", p.id()); o.addProperty("goal", bounded(p.goal(), 300));
            o.addProperty("priority", p.priority()); o.addProperty("added", p.addedTick());
            o.addProperty("deadline", p.deadlineTick()); o.addProperty("onFailure", p.onFailure().name()); plans.add(o);
        }
        root.add("plans", plans);
        JsonArray jobs = new JsonArray();
        for (Job j : queue.jobs()) {
            JsonObject o = new JsonObject(); o.addProperty("id", j.id()); o.addProperty("plan", j.planId());
            o.addProperty("tool", bounded(j.tool(), 128)); o.add("args", j.args()); o.addProperty("priority", j.priority());
            o.addProperty("explicit", j.priorityExplicit()); o.addProperty("order", j.order());
            o.addProperty("state", j.state().name()); o.addProperty("onUnmet", j.onUnmet().name());
            o.addProperty("onFailure", j.onFailure().name()); o.addProperty("maxAttempts", j.maxAttempts());
            o.addProperty("attempts", j.attempts()); o.addProperty("result", bounded(j.result(), 200));
            o.addProperty("retryAt", j.retryAtTick());
            JsonArray guards = new JsonArray(); j.guards().forEach(g -> guards.add(g.toJson())); o.add("guards", guards);
            JsonArray after = new JsonArray(); j.after().forEach(after::add); o.add("after", after); jobs.add(o);
        }
        root.add("jobs", jobs);
        JsonArray archives = new JsonArray();
        for (PlanArchive a : queue.archives()) {
            JsonObject o = new JsonObject(); o.addProperty("id", a.id()); o.addProperty("goal", bounded(a.goal(), 300));
            o.addProperty("success", a.success()); o.addProperty("steps", a.stepCount());
            o.addProperty("summary", bounded(a.summary(), 200)); o.addProperty("finished", a.finishedTick()); archives.add(o);
        }
        root.add("archives", archives);
        long active = queue.active().map(Job::id).orElse(0L); root.addProperty("active", active);
        if (activeTaskSave != null && !activeTaskSave.isBlank()) root.addProperty("activeTask", bounded(activeTaskSave, 8_192));
        String result = PREFIX + root;
        if (result.getBytes(StandardCharsets.UTF_8).length > MAX_SAVE_CHARS) throw new IllegalStateException("queue save exceeds size limit");
        return result;
    }

    public static Optional<Decoded> decode(String text, QueueLimits limits) {
        if (text == null || text.length() > MAX_SAVE_CHARS || text.getBytes(StandardCharsets.UTF_8).length > MAX_SAVE_CHARS || !text.startsWith(PREFIX)) return Optional.empty();
        try {
            JsonObject root = JsonParser.parseString(text.substring(PREFIX.length())).getAsJsonObject();
            JsonArray planRows = root.getAsJsonArray("plans"), jobRows = root.getAsJsonArray("jobs"), archiveRows = root.getAsJsonArray("archives");
            if (planRows == null || jobRows == null || archiveRows == null) return Optional.empty();
            List<PlanRecord> plans = new ArrayList<>();
            for (var e : planRows) { JsonObject p=e.getAsJsonObject(); plans.add(new PlanRecord(p.get("id").getAsLong(), field(p,"goal"), p.get("priority").getAsInt(), p.get("added").getAsLong(), p.get("deadline").getAsLong(), FailurePolicy.valueOf(p.get("onFailure").getAsString()))); }
            List<Job> jobs = new ArrayList<>();
            for (var e : jobRows) { JsonObject j=e.getAsJsonObject(); jobs.add(readJob(j)); }
            List<PlanArchive> archives = new ArrayList<>();
            for (var e : archiveRows) { JsonObject a=e.getAsJsonObject(); archives.add(new PlanArchive(a.get("id").getAsLong(), field(a,"goal"), a.get("success").getAsBoolean(), a.get("steps").getAsInt(), field(a,"summary"), a.get("finished").getAsLong())); }
            int argumentChars = jobs.stream().mapToInt(j -> j.args().toString().length()).sum();
            if (jobs.size() > limits.maxJobs() || plans.size() > limits.maxPlans() || archives.size() > limits.maxArchives()
                    || argumentChars > limits.maxQueueArgsChars()
                    || jobs.stream().anyMatch(j -> j.args().toString().length() > limits.maxArgsChars())) return Optional.empty();
            long active = root.get("active").getAsLong();
            long runningCount = jobs.stream().filter(j -> j.state() == JobState.RUNNING).count();
            if (runningCount > 1) return Optional.empty();
            boolean activeFound = false;
            for (Job job : jobs) if (job.id() == active && job.state() == JobState.RUNNING) activeFound = true;
            if (active != 0 && !activeFound) active = 0;
            if (active == 0 && runningCount == 1) return Optional.empty();
            JobQueue q = JobQueue.restore(limits, root.get("clock").getAsLong(), root.get("nextJob").getAsLong(), root.get("nextPlan").getAsLong(), root.get("nextOrder").getAsLong(), plans, jobs, archives);
            return Optional.of(new Decoded(q, active, root.has("activeTask") ? field(root,"activeTask") : ""));
        } catch (RuntimeException ignored) { return Optional.empty(); }
    }

    private static Job readJob(JsonObject j) {
        List<Guard> guards = new ArrayList<>(); for (var e : j.getAsJsonArray("guards")) guards.add(Guard.parse(e.getAsJsonObject()).orElseThrow());
        List<Long> after = new ArrayList<>(); for (var e : j.getAsJsonArray("after")) after.add(e.getAsLong());
        return new Job(j.get("id").getAsLong(), j.get("plan").getAsLong(), field(j,"tool"), j.getAsJsonObject("args"),
                j.get("priority").getAsInt(), j.get("explicit").getAsBoolean(), j.get("order").getAsLong(),
                JobState.valueOf(j.get("state").getAsString()), guards, GuardAction.valueOf(j.get("onUnmet").getAsString()),
                FailurePolicy.valueOf(j.get("onFailure").getAsString()), j.get("maxAttempts").getAsInt(), j.get("attempts").getAsInt(),
                after, field(j,"result"), j.get("retryAt").getAsLong());
    }
    private static String bounded(String value) { return bounded(value, MAX_FIELD_CHARS); }
    private static String bounded(String value, int max) { return value == null ? "" : value.substring(0, Math.min(value.length(), max)); }
    private static String field(JsonObject object, String name) { String value=object.get(name).getAsString(); if(value.length()>MAX_FIELD_CHARS)throw new IllegalArgumentException("field too long"); return value; }
}
