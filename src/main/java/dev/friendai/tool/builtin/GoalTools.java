package dev.friendai.tool.builtin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.friendai.agent.goal.Criterion;
import dev.friendai.agent.goal.Goal;
import dev.friendai.agent.goal.GoalBoard;
import dev.friendai.agent.goal.GoalResult;
import dev.friendai.agent.goal.GoalService;
import dev.friendai.agent.goal.GoalStatus;
import dev.friendai.core.FriendaiServerState;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.queue.Guard;
import dev.friendai.tool.AccessLevel;
import dev.friendai.tool.SchemaBuilder;
import dev.friendai.tool.ToolRegistry;
import dev.friendai.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The goal tools: one primary goal and a few background goals that outlive plans, deaths and reloads. */
final class GoalTools {
    private GoalTools() {}

    static void register(ToolRegistry<CompanionEntity> registry) {
        JsonObject text = criterionSchema();
        registry.register(ToolKit.callerTool("set_goal",
                "Record what you are working toward so it survives plans, failures and restarts. Use it for a "
                        + "multi-step objective, not for a single action. Each criterion has a short text; add a "
                        + "type with its fields (for example type has_item, item minecraft:oak_log, count 12) when "
                        + "the world can check it by itself. One primary goal at a time; background:true adds one "
                        + "of at most three side goals.",
                SchemaBuilder.object()
                        .string("text", "The goal in one short sentence")
                        .array("criteria", "How you will know it is done", text, 0, 6)
                        .bool("background", "Add as a background goal instead of the primary one")
                        .required("text").build(),
                AccessLevel.TRUSTED, () -> true, (c, caller, args) -> withBoard(c, (owner, goals, board) -> {
                    GoalResult<Goal> result = board.set(
                            args.get("text").getAsString(),
                            criteria(args),
                            args.has("background") && args.get("background").getAsBoolean(),
                            c.level().getGameTime());
                    return result.ok() ? describe("set", result.value()) : ToolResult.error(result.error());
                })));
        registry.register(ToolKit.callerTool("update_goal",
                "Change a goal's text, criteria or status (active or blocked). Use finish_goal to end it.",
                SchemaBuilder.object()
                        .integer("id", "Goal id")
                        .string("text", "New goal sentence")
                        .array("criteria", "Replacement criteria", text, 0, 6)
                        .enumeration("status", "New status", "active", "blocked")
                        .required("id").build(),
                AccessLevel.TRUSTED, () -> true, (c, caller, args) -> withBoard(c, (owner, goals, board) -> {
                    Optional<List<Criterion>> criteria = args.has("criteria")
                            ? Optional.of(criteria(args)) : Optional.empty();
                    Optional<GoalStatus> status = args.has("status")
                            ? Optional.of(GoalStatus.valueOf(args.get("status").getAsString().toUpperCase(java.util.Locale.ROOT)))
                            : Optional.empty();
                    GoalResult<Goal> result = board.update(
                            args.get("id").getAsLong(),
                            args.has("text") ? Optional.of(args.get("text").getAsString()) : Optional.empty(),
                            criteria, status);
                    return result.ok() ? describe("updated", result.value()) : ToolResult.error(result.error());
                })));
        registry.register(ToolKit.callerTool("finish_goal",
                "End a goal: done:true when its criteria are met, done:false to abandon it. Give a short reason.",
                SchemaBuilder.object()
                        .integer("id", "Goal id")
                        .bool("done", "True if achieved, false if abandoned")
                        .string("reason", "Why it ended")
                        .required("id", "done").build(),
                AccessLevel.TRUSTED, () -> true, (c, caller, args) -> withBoard(c, (owner, goals, board) -> {
                    GoalResult<Goal> result = board.finish(
                            args.get("id").getAsLong(),
                            args.get("done").getAsBoolean(),
                            args.has("reason") ? args.get("reason").getAsString() : "");
                    return result.ok() ? describe("finished", result.value()) : ToolResult.error(result.error());
                })));
    }

    /** Links a freshly queued plan to the primary goal when that goal is active and has no plan yet. */
    static void linkPlan(CompanionEntity c, long planId) {
        UUID owner = c.ownerUuid();
        if (owner == null || !(c.level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return;
        }
        GoalService goals = FriendaiServerState.get(level.getServer()).goals();
        goals.board(owner).primary()
                .filter(g -> g.status() == GoalStatus.ACTIVE && g.planId() == 0)
                .ifPresent(g -> {
                    goals.board(owner).linkPlan(g.id(), planId);
                    goals.save(owner);
                });
    }

    private interface BoardAction {
        ToolResult run(UUID owner, GoalService goals, GoalBoard board);
    }

    private static ToolResult withBoard(CompanionEntity c, BoardAction action) {
        UUID owner = c.ownerUuid();
        if (owner == null || !(c.level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return ToolResult.error("This companion has no owner, so goals cannot be saved.");
        }
        GoalService goals = FriendaiServerState.get(level.getServer()).goals();
        ToolResult result = action.run(owner, goals, goals.board(owner));
        goals.save(owner);
        return result;
    }

    private static JsonObject criterionSchema() {
        JsonObject item = new JsonObject();
        item.addProperty("type", "object");
        JsonObject props = new JsonObject();
        for (String name : List.of("text", "type", "item", "block", "dimension")) {
            JsonObject p = new JsonObject();
            p.addProperty("type", "string");
            props.add(name, p);
        }
        for (String name : List.of("count", "slots", "percent", "blocks", "radius")) {
            JsonObject p = new JsonObject();
            p.addProperty("type", "integer");
            props.add(name, p);
        }
        item.add("properties", props);
        return item;
    }

    private static List<Criterion> criteria(JsonObject args) {
        List<Criterion> list = new ArrayList<>();
        if (args.has("criteria") && args.get("criteria").isJsonArray()) {
            for (JsonElement element : args.getAsJsonArray("criteria")) {
                if (element.isJsonObject()) {
                    list.add(parseCriterion(element.getAsJsonObject()));
                }
            }
        }
        return list;
    }

    /** An object with a valid guard {@code type} keeps its check; otherwise only its text counts. */
    static Criterion parseCriterion(JsonObject object) {
        String text = object.has("text") && object.get("text").isJsonPrimitive()
                ? object.get("text").getAsString().strip() : "";
        Optional<Guard> guard = object.has("type") ? Guard.parse(object) : Optional.empty();
        if (text.isEmpty() && guard.isPresent()) {
            text = guard.get().describe();
        }
        return new Criterion(text, guard);
    }

    private static ToolResult describe(String verb, Goal goal) {
        return ToolResult.ok("goal #" + goal.id() + " " + verb + ": " + goal.text()
                + (goal.background() ? " (background)" : "") + " [" + goal.status().name().toLowerCase(java.util.Locale.ROOT) + "]");
    }
}
