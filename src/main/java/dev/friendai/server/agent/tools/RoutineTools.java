package dev.friendai.server.agent.tools;

import com.google.gson.JsonObject;
import dev.friendai.agent.tools.Cost;
import dev.friendai.agent.tools.ToolGroup;
import dev.friendai.agent.tools.ToolMeta;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.SchemaBuilder;
import dev.friendai.execution.tool.Tool;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.server.agent.RoutineDriver;
import dev.friendai.server.execution.tools.FeatureToolContributor;
import dev.friendai.server.execution.tools.FeatureToolRegistry;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Owner-only tools for the one recurring routine: set, list and cancel. */
public final class RoutineTools implements FeatureToolContributor {
    private static final String SET = "set_routine";
    private static final String LIST = "list_routines";
    private static final String CANCEL = "cancel_routine";

    private final Function<CompanionEntity, RoutineDriver> routines;

    public RoutineTools(Function<CompanionEntity, RoutineDriver> routines) {
        this.routines = Objects.requireNonNull(routines);
    }

    @Override
    public void register(FeatureToolRegistry registry) {
        registry.registerTool(
                callerTool(
                        SET,
                        "Set the owner's one recurring routine: a short list of existing tools that runs at a game-time "
                                + "phase or every N game minutes. It enters the normal queue, waits for work that can "
                                + "run now, and survives a restart. A success is silent unless the owner asked to be "
                                + "told, while a new failure is reported once with its reason. Replacing a running "
                                + "routine is refused; cancel it first.",
                        SchemaBuilder.object()
                                .string("name", "Short name, 1 to 40 characters")
                                .enumeration(
                                        "phase", "Run once a day at this phase", "morning", "day", "evening", "night")
                                .integer(
                                        "interval_min",
                                        "Run every N game minutes instead of a phase",
                                        dev.friendai.agent.routine.Routine.MIN_INTERVAL_MINUTES,
                                        dev.friendai.agent.routine.Routine.MAX_INTERVAL_MINUTES)
                                .array(
                                        "steps",
                                        "Existing plannable tools in order; args is a JSON object encoded as a string",
                                        stepSchema(),
                                        1,
                                        dev.friendai.agent.routine.Routine.MAX_STEPS)
                                .integer(
                                        "max_runs",
                                        "Stop after this many runs; 0 means until cancelled",
                                        0,
                                        dev.friendai.agent.routine.Routine.MAX_RUNS)
                                .bool("enabled", "Start enabled; default true")
                                .bool("report", "Tell the owner when a run succeeds; default false")
                                .required("name", "steps")
                                .build(),
                        (companion, caller, args) -> {
                            RoutineDriver driver = driver(companion);
                            return driver == null
                                    ? ToolResult.error("routines are unavailable")
                                    : driver.set(companion, caller, args);
                        }),
                metadata(SET));
        registry.registerTool(
                callerTool(
                        LIST,
                        "Show the owner's routine, its next due game ticks and the last outcome.",
                        SchemaBuilder.object().build(),
                        (companion, caller, args) -> {
                            RoutineDriver driver = driver(companion);
                            return driver == null
                                    ? ToolResult.error("routines are unavailable")
                                    : driver.list(companion);
                        }),
                metadata(LIST));
        registry.registerTool(
                callerTool(
                        CANCEL,
                        "Cancel the owner's routine and stop its running plan at the queue's safe boundary.",
                        SchemaBuilder.object()
                                .string("name", "The routine's name")
                                .build(),
                        (companion, caller, args) -> {
                            RoutineDriver driver = driver(companion);
                            return driver == null
                                    ? ToolResult.error("routines are unavailable")
                                    : driver.cancel(companion, caller, args);
                        }),
                metadata(CANCEL));
    }

    private RoutineDriver driver(CompanionEntity companion) {
        RoutineDriver driver = routines.apply(companion);
        return driver;
    }

    private static JsonObject stepSchema() {
        return SchemaBuilder.object()
                .string("tool", "One existing plannable tool name")
                .string("args", "That tool's arguments as a JSON object encoded as a string")
                .required("tool")
                .build();
    }

    private static ToolMeta metadata(String name) {
        return switch (name) {
            case SET ->
                new ToolMeta(
                        name,
                        ToolGroup.AUTONOMY,
                        false,
                        "- When the owner asks for a recurring timetable (\"every morning feed the animals and mine ten logs\"), call set_routine with their exact order as steps and one phase or interval.",
                        List.of(),
                        Cost.LOW);
            case LIST ->
                new ToolMeta(
                        name,
                        ToolGroup.AUTONOMY,
                        false,
                        "- Use list_routines when the owner asks what is scheduled or when it will run next.",
                        List.of(),
                        Cost.LOW);
            case CANCEL ->
                new ToolMeta(
                        name,
                        ToolGroup.AUTONOMY,
                        false,
                        "- Use cancel_routine when the owner no longer wants the timetable.",
                        List.of(),
                        Cost.LOW);
            default -> throw new IllegalArgumentException("No metadata declared for routine tool: " + name);
        };
    }

    private static Tool<CompanionEntity> callerTool(
            String name, String description, JsonObject schema, CallerAction action) {
        return new Tool<>() {
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
                return action.run(companion, new Caller(null, "", AccessLevel.OWNER), args);
            }

            @Override
            public ToolResult execute(CompanionEntity companion, Caller caller, JsonObject args) {
                return action.run(companion, caller, args);
            }
        };
    }

    @FunctionalInterface
    private interface CallerAction {
        ToolResult run(CompanionEntity companion, Caller caller, JsonObject args);
    }
}
