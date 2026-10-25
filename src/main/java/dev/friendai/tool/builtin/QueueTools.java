package dev.friendai.tool.builtin;

import com.google.gson.JsonObject;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.queue.PlanOptions;
import dev.friendai.queue.QueueText;
import dev.friendai.queue.QueuedStep;
import dev.friendai.task.TaskSettings;
import dev.friendai.task.plan.StepFactory;
import dev.friendai.tool.Caller;
import dev.friendai.tool.QueueParser;
import dev.friendai.tool.SchemaBuilder;
import dev.friendai.tool.ToolRegistry;
import dev.friendai.tool.ToolResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** LLM-facing queue operations. */
final class QueueTools {
    private QueueTools() {}
    static void register(ToolRegistry<CompanionEntity> registry, List<TaskToolDef> defs) {
        Map<String, TaskToolDef> all = new LinkedHashMap<>(); defs.stream().filter(TaskToolDef::plannable).forEach(d -> all.put(d.name(), d));
        JsonObject item = SchemaBuilder.object().string("tool", "Task tool name").string("args", "JSON object arguments").required("tool").build();
        registry.register(ToolKit.callerTool("queue_add", "Add a durable multi-step plan to the task queue.", SchemaBuilder.object().string("goal", "Short goal").array("steps", "Queue steps", item, 1, 64).required("steps").build(), dev.friendai.tool.AccessLevel.TRUSTED,
                () -> true, (c, caller, args) -> add(c, caller, args, all)));
        registry.register(ToolKit.instantTool("queue_show", "Show queued work, running work and blocked jobs.", (c, args) -> ToolResult.ok(QueueText.show(c.taskRunner().queue(), emptyFacts(), null, 20, false))));
        registry.register(ToolKit.instantTool("queue_edit", "Change queued work: retry, skip, cancel, clear, or change priority.", SchemaBuilder.object()
                .enumeration("action", "Queue change", "priority", "cancel", "retry", "skip", "clear")
                .integer("job", "Job id").integer("plan", "Plan id").integer("priority", "Priority", -10, 10).required("action").build(),
                (c, args) -> edit(c, args)));
    }
    private static ToolResult add(CompanionEntity c, Caller caller, JsonObject args, Map<String, TaskToolDef> all) {
        Map<String, TaskToolDef> allowed = new LinkedHashMap<>(); all.forEach((name, def) -> { if (caller.access().allows(def.minAccess())) allowed.put(name, def); });
        Map<String, JsonObject> schemas = new LinkedHashMap<>(); allowed.forEach((name, def) -> schemas.put(name, def.schema()));
        var parsed = QueueParser.parse(args.getAsJsonArray("steps"), schemas, all.keySet(), TaskSettings.queueMaxStepsPerAdd, 8192, 49152);
        if (!parsed.ok()) return ToolResult.error("Invalid plan: " + String.join("; ", parsed.errors()));
        List<QueuedStep> steps = parsed.steps(); List<StepFactory> factories = steps.stream().map(s -> (StepFactory) ctx -> allowed.get(s.tool()).factory().create(ctx.companion(), s.args())).toList();
        String goal = args.has("goal") ? args.get("goal").getAsString() : ""; long id = c.taskRunner().enqueuePlan(goal, steps, factories, PlanOptions.DEFAULT);
        return ToolResult.ok("plan #" + id + " \"" + goal + "\" added: " + steps.size() + " jobs");
    }
    private static ToolResult edit(CompanionEntity c, JsonObject args) {
        String action = args.get("action").getAsString(); var queue = c.taskRunner().queue();
        return switch (action) {
            case "clear" -> ToolResult.ok(queue.clear().text());
            case "retry" -> result(queue.retry(args.get("job").getAsLong()));
            case "skip" -> result(queue.skip(args.get("job").getAsLong()));
            case "cancel" -> args.has("plan") ? result(queue.cancelPlan(args.get("plan").getAsLong())) : result(queue.cancel(args.get("job").getAsLong()));
            case "priority" -> args.has("plan") ? result(queue.setPlanPriority(args.get("plan").getAsLong(), args.get("priority").getAsInt())) : result(queue.setPriority(args.get("job").getAsLong(), args.get("priority").getAsInt()));
            default -> ToolResult.error("unknown queue_edit action: " + action);
        };
    }
    private static ToolResult result(dev.friendai.queue.JobQueue.Result result) { return result.ok() ? ToolResult.ok(result.text()) : ToolResult.error(result.text()); }
    private static dev.friendai.queue.GuardFacts emptyFacts() { return new dev.friendai.queue.GuardFacts() { public int countItems(String id){return 0;} public int emptySlots(){return 0;} public float healthFraction(){return 1;} public int ownerDistance(){return -1;} public int countBlocks(String id,int r){return 0;} public String dimension(){return "";} }; }
}
