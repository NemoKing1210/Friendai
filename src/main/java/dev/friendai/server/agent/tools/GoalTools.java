package dev.friendai.server.agent.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.friendai.agent.goal.Criterion;
import dev.friendai.agent.goal.Goal;
import dev.friendai.agent.goal.GoalBoard;
import dev.friendai.agent.goal.GoalResult;
import dev.friendai.agent.goal.GoalService;
import dev.friendai.agent.goal.GoalStatus;
import dev.friendai.agent.tools.Cost;
import dev.friendai.agent.tools.ToolGroup;
import dev.friendai.agent.tools.ToolMeta;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.queue.Guard;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.SchemaBuilder;
import dev.friendai.execution.tool.Tool;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.foundation.teaching.TeachingContext;
import dev.friendai.server.execution.tools.FeatureToolContributor;
import dev.friendai.server.execution.tools.FeatureToolRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

/** Persistent owner goals and their terminal skill outcomes. */
public final class GoalTools implements FeatureToolContributor {
    private static final String SET_GOAL = "set_goal";
    private static final String UPDATE_GOAL = "update_goal";
    private static final String FINISH_GOAL = "finish_goal";

    private final GoalToolAccess access;

    public GoalTools(GoalToolAccess access) {
        this.access = Objects.requireNonNull(access);
    }

    @Override
    public void register(FeatureToolRegistry registry) {
        var criterionSchema = criterionSchema();
        registerTool(
                registry,
                SET_GOAL,
                "Record what you are working toward so it survives plans, failures and restarts. Use it for a "
                        + "multi-step objective, not for a single action. Each criterion has a short text; add a "
                        + "type with its fields (for example type has_item, item minecraft:oak_log, count 12) when "
                        + "the world can check it by itself. One primary goal at a time; background:true adds one "
                        + "of at most three side goals.",
                SchemaBuilder.object()
                        .string("text", "The goal in one short sentence")
                        .array("criteria", "How you will know it is done", criterionSchema, 0, 6)
                        .bool("background", "Add as a background goal instead of the primary one")
                        .required("text")
                        .build(),
                (companion, args) -> withBoard(companion, (owner, goals, board) -> {
                    GoalResult<Goal> result = board.set(
                            args.get("text").getAsString(),
                            criteria(args),
                            args.has("background") && args.get("background").getAsBoolean(),
                            companion.level().getGameTime());
                    return result.ok() ? describe("set", result.value()) : ToolResult.error(result.error());
                }));
        registerTool(
                registry,
                UPDATE_GOAL,
                "Change a goal's text, criteria or status (active or blocked). Use finish_goal to end it.",
                SchemaBuilder.object()
                        .integer("id", "Goal id")
                        .string("text", "New goal sentence")
                        .array("criteria", "Replacement criteria", criterionSchema, 0, 6)
                        .enumeration("status", "New status", "active", "blocked")
                        .required("id")
                        .build(),
                (companion, args) -> withBoard(companion, (owner, goals, board) -> {
                    Optional<List<Criterion>> criteria =
                            args.has("criteria") ? Optional.of(criteria(args)) : Optional.empty();
                    Optional<GoalStatus> status = args.has("status")
                            ? Optional.of(GoalStatus.valueOf(
                                    args.get("status").getAsString().toUpperCase(Locale.ROOT)))
                            : Optional.empty();
                    GoalResult<Goal> result = board.update(
                            args.get("id").getAsLong(),
                            args.has("text") ? Optional.of(args.get("text").getAsString()) : Optional.empty(),
                            criteria,
                            status);
                    return result.ok() ? describe("updated", result.value()) : ToolResult.error(result.error());
                }));
        registerTool(
                registry,
                FINISH_GOAL,
                "End a goal: done:true when its criteria are met, done:false to abandon it. Give a short reason.",
                SchemaBuilder.object()
                        .integer("id", "Goal id")
                        .bool("done", "True if achieved, false if abandoned")
                        .string("reason", "Why it ended")
                        .required("id", "done")
                        .build(),
                (companion, args) -> withBoard(companion, (owner, goals, board) -> {
                    boolean done = args.get("done").getAsBoolean();
                    // A completion claim is checked against the world first: a criterion the world can test must
                    // hold, or the goal stays active and the model is told exactly what is still missing.
                    if (done) {
                        long id = args.get("id").getAsLong();
                        if (board.find(id).isPresent()) {
                            var facts = new dev.friendai.task.queue.CompanionGuardFacts(companion);
                            facts.refresh();
                            var unmet = board.unmet(id, facts);
                            if (!unmet.isEmpty()) {
                                return ToolResult.error("Not done yet: "
                                        + String.join(
                                                "; ",
                                                unmet.stream()
                                                        .map(dev.friendai.agent.goal.Criterion::text)
                                                        .toList()));
                            }
                        }
                    }
                    GoalResult<Goal> result = board.finish(
                            args.get("id").getAsLong(),
                            done,
                            args.has("reason") ? args.get("reason").getAsString() : "");
                    if (result.ok() && companion.level() instanceof ServerLevel level) {
                        access.skillOutcome(
                                level,
                                owner,
                                "goal:" + result.value().id(),
                                done
                                        ? TeachingContext.OutcomeKind.GOAL_SUCCESS
                                        : TeachingContext.OutcomeKind.GOAL_FAILURE,
                                result.value().text());
                    }
                    return result.ok() ? describe("finished", result.value()) : ToolResult.error(result.error());
                }));
    }

    private void registerTool(
            FeatureToolRegistry registry, String name, String description, JsonObject schema, Action action) {
        registry.registerTool(
                new Tool<>() {
                    @Override
                    public String name() {
                        return name;
                    }

                    @Override
                    public String description() {
                        return description;
                    }

                    @Override
                    public JsonObject schema() {
                        return schema;
                    }

                    @Override
                    public AccessLevel minAccess() {
                        return AccessLevel.OWNER;
                    }

                    @Override
                    public ToolResult execute(CompanionEntity companion, JsonObject args) {
                        return action.run(companion, args);
                    }
                },
                metadata(name));
    }

    private ToolResult withBoard(CompanionEntity companion, BoardAction action) {
        UUID owner = companion.ownerUuid();
        if (owner == null || !(companion.level() instanceof ServerLevel level)) {
            return ToolResult.error("This companion has no owner, so goals cannot be saved.");
        }
        GoalService goals = access.goals(level);
        ToolResult result = action.run(owner, goals, goals.board(owner));
        goals.save(owner);
        return result;
    }

    private static JsonObject criterionSchema() {
        JsonObject item = new JsonObject();
        item.addProperty("type", "object");
        JsonObject props = new JsonObject();
        for (String name : List.of("text", "type", "item", "block", "dimension")) {
            JsonObject property = new JsonObject();
            property.addProperty("type", "string");
            props.add(name, property);
        }
        for (String name : List.of("count", "slots", "percent", "blocks", "radius")) {
            JsonObject property = new JsonObject();
            property.addProperty("type", "integer");
            props.add(name, property);
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
                ? object.get("text").getAsString().strip()
                : "";
        Optional<Guard> guard = object.has("type") ? Guard.parse(object) : Optional.empty();
        if (text.isEmpty() && guard.isPresent()) {
            text = guard.get().describe();
        }
        return new Criterion(text, guard);
    }

    private static ToolMeta metadata(String name) {
        return switch (name) {
            case SET_GOAL, UPDATE_GOAL, FINISH_GOAL ->
                new ToolMeta(name, ToolGroup.CORE, true, "", List.of(), Cost.LOW);
            default -> throw new IllegalArgumentException("No metadata declared for goal tool: " + name);
        };
    }

    private static ToolResult describe(String verb, Goal goal) {
        return ToolResult.ok("goal #" + goal.id() + " " + verb + ": " + goal.text()
                + (goal.background() ? " (background)" : "") + " ["
                + goal.status().name().toLowerCase(Locale.ROOT)
                + "]");
    }

    @FunctionalInterface
    private interface Action {
        ToolResult run(CompanionEntity companion, JsonObject args);
    }

    @FunctionalInterface
    private interface BoardAction {
        ToolResult run(UUID owner, GoalService goals, GoalBoard board);
    }
}
