package dev.friedai.tool.builtin;

import com.google.gson.JsonObject;
import dev.friedai.entity.CompanionEntity;
import dev.friedai.task.TaskSettings;
import dev.friedai.task.plan.PlanEntry;
import dev.friedai.task.plan.SequenceTask;
import dev.friedai.tool.PlanParser;
import dev.friedai.tool.PlanStep;
import dev.friedai.tool.SchemaBuilder;
import dev.friedai.tool.ToolRegistry;
import dev.friedai.tool.ToolResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Control over what the companion is doing. */
final class ControlTools {
    private ControlTools() {}

    static void register(ToolRegistry<CompanionEntity> registry, List<TaskToolDef> defs) {
        registry.register(ToolKit.instantTool("stop", "Cancel the current task and go back to the default activity.", (c, a) -> {
            c.taskRunner().stop("stopped by the LLM");
            return ToolResult.ok("stopped");
        }));

        List<TaskToolDef> plannable = defs.stream().filter(TaskToolDef::plannable).toList();
        Map<String, TaskToolDef> byName = new LinkedHashMap<>();
        Map<String, JsonObject> schemas = new LinkedHashMap<>();
        for (TaskToolDef def : plannable) {
            byName.put(def.name(), def);
            schemas.put(def.name(), def.schema());
        }
        Set<String> allTools = new LinkedHashSet<>();
        registry.specs().forEach(spec -> allTools.add(spec.name()));
        allTools.add("run_plan");

        registry.register(ToolKit.instantTool("run_plan",
                "Run several actions in order as one task. Use it for goals that need more than one step. Stops at the first failed step and reports which step failed and why.",
                SchemaBuilder.object()
                        .string("goal", "Short description of what the plan is for")
                        .array("steps", "The actions to run, in order", SchemaBuilder.object()
                                .enumeration("tool", "Tool to run", plannable.stream().map(TaskToolDef::name).toArray(String[]::new))
                                .string("args", "JSON object with the tool's arguments, e.g. {\"block\":\"#minecraft:logs\",\"count\":3}")
                                .required("tool")
                                .build(), 1, PlanParser.MAX_STEPS)
                        .required("steps")
                        .build(),
                (c, a) -> {
                    PlanParser.Result parsed = PlanParser.parse(a.getAsJsonArray("steps"), schemas, allTools);
                    if (!parsed.ok()) {
                        return ToolResult.error("Invalid plan: " + String.join("; ", parsed.errors()));
                    }
                    List<PlanEntry> entries = new ArrayList<>();
                    for (PlanStep step : parsed.steps()) {
                        TaskToolDef def = byName.get(step.tool());
                        entries.add(new PlanEntry(step.tool(), ctx -> def.factory().create(ctx.companion(), step.args())));
                    }
                    String goal = a.has("goal") && !a.get("goal").isJsonNull() ? a.get("goal").getAsString() : null;
                    SequenceTask plan = new SequenceTask(goal, entries, () -> TaskSettings.taskTimeoutSec * 20);
                    int id = c.taskRunner().start(plan, "run_plan");
                    return ToolResult.ok("task #" + id + " started: " + plan.describe());
                }));
    }
}
