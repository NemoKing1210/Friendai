package dev.friendai.tool.builtin;

import com.google.gson.JsonObject;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.task.Task;
import dev.friendai.task.TaskSettings;
import dev.friendai.task.combat.CombatController;
import dev.friendai.task.plan.PlanEntry;
import dev.friendai.task.plan.SequenceTask;
import dev.friendai.tool.PlanParser;
import dev.friendai.tool.PlanStep;
import dev.friendai.tool.SchemaBuilder;
import dev.friendai.tool.StopScope;
import dev.friendai.tool.ToolRegistry;
import dev.friendai.tool.ToolResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Control over what the companion is doing. */
final class ControlTools {
    private static final String STOP_REASON = "stopped by the LLM";

    private ControlTools() {}

    private static ToolResult stop(CompanionEntity c, String what) {
        return switch (StopScope.parse(what)) {
            case StopScope.Current ignored -> {
                c.taskRunner().stop(STOP_REASON);
                yield ToolResult.ok("stopped");
            }
            case StopScope.All ignored -> stopEverything(c);
            case StopScope.Named named -> {
                // The model writes "mine blocks" as often as "mine_blocks".
                String name = named.name().replaceAll("\\s+", "_");
                Optional<String> stopped = c.taskRunner().stopNamed(name, STOP_REASON);
                if (stopped.isPresent()) {
                    yield ToolResult.ok("stopped " + stopped.get());
                }
                String current = c.taskRunner().current().map(Task::describe).orElse("none");
                yield ToolResult.error("not running: " + name + "; current task: " + current);
            }
        };
    }

    private static ToolResult stopEverything(CompanionEntity c) {
        String task = c.taskRunner().current().map(Task::describe).orElse("no task");
        c.taskRunner().stop(STOP_REASON);
        StringBuilder text = new StringBuilder("stopped everything: ").append(task);
        CombatController.State before = c.combatController().state();
        c.combatController().stopFight();
        if (c.combatController().state() != before) {
            text.append("; ended the fight");
        }
        int jobs = c.furnaceJobs().clear();
        if (jobs > 0) {
            text.append("; dropped ").append(jobs).append(jobs == 1 ? " furnace job" : " furnace jobs");
        }
        return ToolResult.ok(text.toString());
    }

    static void register(ToolRegistry<CompanionEntity> registry, List<TaskToolDef> defs) {
        registry.register(ToolKit.instantTool("stop",
                "Cancel the current task and go back to the default activity. Pass what to stop a specific action by its tool name"
                        + " (for example mine_blocks) or everything (the task, a fight, furnace jobs). To stop following the owner, use stay.",
                SchemaBuilder.object()
                        .string("what", "current (default), a tool name like mine_blocks, or all")
                        .build(),
                (c, a) -> stop(c, a.has("what") && !a.get("what").isJsonNull() ? a.get("what").getAsString() : null)));

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
                        entries.add(new PlanEntry(step.tool(), step.args(),
                                ctx -> def.factory().create(ctx.companion(), step.args())));
                    }
                    String goal = a.has("goal") && !a.get("goal").isJsonNull() ? a.get("goal").getAsString() : null;
                    SequenceTask plan = new SequenceTask(goal, entries, () -> TaskSettings.taskTimeoutSec * 20);
                    int id = c.taskRunner().start(plan, "run_plan");
                    return ToolResult.ok("task #" + id + " started: " + plan.describe());
                }));
    }
}
