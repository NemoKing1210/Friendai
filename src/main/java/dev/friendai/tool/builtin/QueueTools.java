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
    private static dev.friendai.queue.GuardFacts emptyFacts() { return new dev.friendai.queue.GuardFacts() { public int countItems(String id){return 0;} public int emptySlots(){return 0;} public float healthFraction(){return 1;} public int ownerDistance(){return -1;} public int countBlocks(String id,int r){return 0;} public String dimension(){return "";} }; }
}
