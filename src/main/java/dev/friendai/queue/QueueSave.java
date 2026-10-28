package dev.friendai.queue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Bounded queue snapshot codec. The enclosing execution document owns format versioning. */
public final class QueueSave {
    public static final int MAX_SAVE_CHARS = 1_000_000;
    public static final int MAX_FIELD_CHARS = 8_192;

    private QueueSave() {}

    public record Decoded(JobQueue queue, long activeJobId) {}

    public static JsonObject encode(JobQueue queue, long activeJobId) {
        JsonObject root = new JsonObject();
        root.addProperty("clock", queue.clock());
        root.addProperty("nextJob", queue.nextJobId());
        root.addProperty("nextPlan", queue.nextPlanId());
        root.addProperty("nextOrder", queue.nextOrder());
        JsonArray plans = new JsonArray();
        for (PlanRecord p : queue.plans()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", p.id());
            o.addProperty("goal", bounded(p.goal(), 300));
            o.addProperty("priority", p.priority());
            o.addProperty("added", p.addedTick());
            o.addProperty("deadline", p.deadlineTick());
            o.addProperty("onFailure", p.onFailure().name());
            if (p.definition() != null) o.add("definition", PlanCodec.toJson(p.definition()));
            if (p.cursor() != null) o.add("cursor", cursorJson(p.cursor()));
            plans.add(o);
        }
        root.add("plans", plans);
        JsonArray jobs = new JsonArray();
        for (Job j : queue.jobs()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", j.id());
            o.addProperty("plan", j.planId());
            o.addProperty("tool", bounded(j.tool(), 128));
            o.add("args", j.args());
            o.addProperty("priority", j.priority());
            o.addProperty("explicit", j.priorityExplicit());
            o.addProperty("order", j.order());
            o.addProperty("state", j.state().name());
            o.addProperty("onUnmet", j.onUnmet().name());
            o.addProperty("onFailure", j.onFailure().name());
            o.addProperty("maxAttempts", j.maxAttempts());
            o.addProperty("attempts", j.attempts());
            o.addProperty("result", bounded(j.result(), 200));
            o.addProperty("retryAt", j.retryAtTick());
            if (j.principal() != null) o.addProperty("principal", j.principal().toString());
            if (!j.creativeSensitive()) o.addProperty("creativeSensitive", false);
            JsonArray guards = new JsonArray();
            j.guards().forEach(g -> guards.add(g.toJson()));
            o.add("guards", guards);
            JsonArray after = new JsonArray();
            j.after().forEach(after::add);
            o.add("after", after);
            jobs.add(o);
        }
        root.add("jobs", jobs);
        JsonArray archives = new JsonArray();
        for (PlanArchive a : queue.archives()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", a.id());
            o.addProperty("goal", bounded(a.goal(), 300));
            o.addProperty("success", a.success());
            o.addProperty("steps", a.stepCount());
            o.addProperty("summary", bounded(a.summary(), 200));
            o.addProperty("finished", a.finishedTick());
            archives.add(o);
        }
        root.add("archives", archives);
        root.addProperty("active", activeJobId);
        String result = root.toString();
        if (result.getBytes(StandardCharsets.UTF_8).length > MAX_SAVE_CHARS)
            throw new IllegalStateException("queue save exceeds size limit");
        return root;
    }

    public static Optional<Decoded> decode(JsonObject root, QueueLimits limits) {
        if (root == null || root.toString().getBytes(StandardCharsets.UTF_8).length > MAX_SAVE_CHARS)
            return Optional.empty();
        try {
            JsonArray planRows = root.getAsJsonArray("plans"),
                    jobRows = root.getAsJsonArray("jobs"),
                    archiveRows = root.getAsJsonArray("archives");
            if (planRows == null || jobRows == null || archiveRows == null) return Optional.empty();
            List<JsonObject> planObjects = new ArrayList<>();
            for (var e : planRows) planObjects.add(e.getAsJsonObject());
            List<Job> jobs = new ArrayList<>();
            for (var e : jobRows) {
                JsonObject j = e.getAsJsonObject();
                jobs.add(readJob(j));
            }
            List<PlanRecord> plans = new ArrayList<>();
            for (JsonObject p : planObjects) {
                long id = p.get("id").getAsLong();
                PlanDefinition definition =
                        p.has("definition") ? PlanCodec.fromJson(p.getAsJsonArray("definition")) : null;
                PlanCursor cursor = null;
                if (p.has("cursor")) {
                    if (definition == null) return Optional.empty();
                    cursor = readCursor(p.getAsJsonObject("cursor"));
                }
                plans.add(new PlanRecord(
                        id,
                        field(p, "goal"),
                        p.get("priority").getAsInt(),
                        p.get("added").getAsLong(),
                        p.get("deadline").getAsLong(),
                        FailurePolicy.valueOf(p.get("onFailure").getAsString()),
                        definition,
                        cursor));
            }
            for (PlanRecord plan : plans)
                if (plan.cursor() != null && !plan.cursor().pendingActionPath().isEmpty()) {
                    Job linked = jobs.stream()
                            .filter(job -> job.id() == plan.cursor().pendingJobId() && job.planId() == plan.id())
                            .findFirst()
                            .orElse(null);
                    if (linked == null
                            || !linked.tool()
                                    .equals(plan.cursor().pendingAction().tool())
                            || !linked.args()
                                    .equals(plan.cursor().pendingAction().args())) return Optional.empty();
                }
            List<PlanArchive> archives = new ArrayList<>();
            for (var e : archiveRows) {
                JsonObject a = e.getAsJsonObject();
                archives.add(new PlanArchive(
                        a.get("id").getAsLong(),
                        field(a, "goal"),
                        a.get("success").getAsBoolean(),
                        a.get("steps").getAsInt(),
                        field(a, "summary"),
                        a.get("finished").getAsLong()));
            }
            int argumentChars =
                    jobs.stream().mapToInt(j -> j.args().toString().length()).sum();
            if (jobs.size() > limits.maxJobs()
                    || plans.size() > limits.maxPlans()
                    || archives.size() > limits.maxArchives()
                    || argumentChars > limits.maxQueueArgsChars()
                    || jobs.stream().anyMatch(j -> j.args().toString().length() > limits.maxArgsChars()))
                return Optional.empty();
            long active = root.get("active").getAsLong();
            long runningCount =
                    jobs.stream().filter(j -> j.state() == JobState.RUNNING).count();
            if (runningCount > 1) return Optional.empty();
            boolean activeFound = false;
            for (Job job : jobs) if (job.id() == active && job.state() == JobState.RUNNING) activeFound = true;
            if (active != 0 && !activeFound) active = 0;
            if (active == 0 && runningCount == 1) return Optional.empty();
            JobQueue q = JobQueue.restore(
                    limits,
                    root.get("clock").getAsLong(),
                    root.get("nextJob").getAsLong(),
                    root.get("nextPlan").getAsLong(),
                    root.get("nextOrder").getAsLong(),
                    plans,
                    jobs,
                    archives);
            return Optional.of(new Decoded(q, active));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static Job readJob(JsonObject j) {
        List<Guard> guards = new ArrayList<>();
        for (var e : j.getAsJsonArray("guards"))
            guards.add(Guard.parse(e.getAsJsonObject()).orElseThrow());
        List<Long> after = new ArrayList<>();
        for (var e : j.getAsJsonArray("after")) after.add(e.getAsLong());
        return new Job(
                j.get("id").getAsLong(),
                j.get("plan").getAsLong(),
                field(j, "tool"),
                j.getAsJsonObject("args"),
                j.get("priority").getAsInt(),
                j.get("explicit").getAsBoolean(),
                j.get("order").getAsLong(),
                JobState.valueOf(j.get("state").getAsString()),
                guards,
                GuardAction.valueOf(j.get("onUnmet").getAsString()),
                FailurePolicy.valueOf(j.get("onFailure").getAsString()),
                j.get("maxAttempts").getAsInt(),
                j.get("attempts").getAsInt(),
                after,
                field(j, "result"),
                j.get("retryAt").getAsLong(),
                j.has("principal") ? java.util.UUID.fromString(field(j, "principal")) : null,
                !j.has("creativeSensitive") || j.get("creativeSensitive").getAsBoolean());
    }

    private static JsonObject cursorJson(PlanCursor cursor) {
        JsonObject json = new JsonObject();
        json.addProperty("startTick", cursor.startTick());
        json.addProperty("startDistance", cursor.startDistance());
        JsonArray stack = new JsonArray();
        for (PlanCursor.Frame frame : cursor.stack()) {
            JsonObject f = new JsonObject();
            JsonArray route = new JsonArray();
            frame.route().forEach(route::add);
            f.add("route", route);
            f.addProperty("nextIndex", frame.nextIndex());
            f.addProperty("pathPrefix", frame.pathPrefix());
            f.addProperty("ownerPath", frame.ownerPath());
            f.addProperty("repeatBody", frame.repeatBody());
            stack.add(f);
        }
        json.add("stack", stack);
        JsonObject branches = new JsonObject();
        cursor.selectedBranches().forEach(branches::addProperty);
        json.add("branches", branches);
        JsonObject repeats = new JsonObject();
        cursor.repeatIterations().forEach(repeats::addProperty);
        json.add("repeats", repeats);
        json.addProperty("pendingPath", cursor.pendingActionPath());
        json.addProperty("pendingJob", cursor.pendingJobId());
        if (cursor.pendingAction() != null) json.add("pendingAction", PlanCodec.actionJson(cursor.pendingAction()));
        return json;
    }

    private static PlanCursor readCursor(JsonObject json) {
        List<PlanCursor.Frame> stack = new ArrayList<>();
        for (var e : json.getAsJsonArray("stack")) {
            JsonObject f = e.getAsJsonObject();
            List<Integer> route = new ArrayList<>();
            for (var part : f.getAsJsonArray("route")) route.add(part.getAsInt());
            stack.add(new PlanCursor.Frame(
                    route,
                    f.get("nextIndex").getAsInt(),
                    field(f, "pathPrefix"),
                    field(f, "ownerPath"),
                    f.get("repeatBody").getAsBoolean()));
        }
        Map<String, Boolean> branches = new java.util.LinkedHashMap<>();
        for (String key : json.getAsJsonObject("branches").keySet())
            branches.put(key, json.getAsJsonObject("branches").get(key).getAsBoolean());
        Map<String, Integer> repeats = new java.util.LinkedHashMap<>();
        for (String key : json.getAsJsonObject("repeats").keySet())
            repeats.put(key, json.getAsJsonObject("repeats").get(key).getAsInt());
        String pendingPath = field(json, "pendingPath");
        QueuedStep pending =
                json.has("pendingAction") ? PlanCodec.readAction(json.getAsJsonObject("pendingAction")) : null;
        long pendingJob = json.has("pendingJob") ? json.get("pendingJob").getAsLong() : 0;
        return new PlanCursor(
                stack,
                branches,
                repeats,
                json.get("startTick").getAsLong(),
                json.get("startDistance").getAsLong(),
                pendingPath,
                pending,
                pendingJob);
    }

    private static String bounded(String value) {
        return bounded(value, MAX_FIELD_CHARS);
    }

    private static String bounded(String value, int max) {
        return value == null ? "" : value.substring(0, Math.min(value.length(), max));
    }

    private static String field(JsonObject object, String name) {
        String value = object.get(name).getAsString();
        if (value.length() > MAX_FIELD_CHARS) throw new IllegalArgumentException("field too long");
        return value;
    }
}
