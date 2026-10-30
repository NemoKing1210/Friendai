package dev.friendai.server.agent.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.friendai.agent.autonomy.FreeTime;
import dev.friendai.agent.autonomy.NeedKind;
import dev.friendai.agent.tools.Cost;
import dev.friendai.agent.tools.ToolGroup;
import dev.friendai.agent.tools.ToolMeta;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.SchemaBuilder;
import dev.friendai.execution.tool.Tool;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.server.agent.FreeTimeDriver;
import dev.friendai.server.execution.tools.FeatureToolContributor;
import dev.friendai.server.execution.tools.FeatureToolRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/** Registers owner-only tools for starting and directing the companion's free time. */
public final class FreeTimeTools implements FeatureToolContributor {
    private static final String START_FREE_TIME = "start_free_time";
    private static final String END_FREE_TIME = "end_free_time";
    private static final String CHOOSE_ACTIVITY = "choose_activity";

    private final Function<CompanionEntity, FreeTimeDriver> freeTimeDriver;

    public FreeTimeTools(Supplier<FreeTimeDriver> freeTimeDriver) {
        this(ignored -> freeTimeDriver.get());
    }

    public FreeTimeTools(Function<CompanionEntity, FreeTimeDriver> freeTimeDriver) {
        this.freeTimeDriver = Objects.requireNonNull(freeTimeDriver);
    }

    @Override
    public void register(FeatureToolRegistry registry) {
        registry.registerTool(
                callerTool(
                        START_FREE_TIME,
                        "Start free time: you work on your own, like a survival player, until the owner calls you back. "
                                + "Use it when the owner says to do your own thing or whatever you want. Pass their hint "
                                + "as wish.",
                        SchemaBuilder.object()
                                .string("wish", "The owner's hint, like 'focus on food'; optional")
                                .build(),
                        (companion, caller, args) -> {
                            FreeTimeDriver driver = freeTimeDriver.apply(companion);
                            if (driver.disabled()) {
                                return ToolResult.error("free time is disabled on this server");
                            }
                            String wish = args.has("wish") && !args.get("wish").isJsonNull()
                                    ? args.get("wish").getAsString()
                                    : "";
                            driver.start(companion, FreeTime.Origin.COMMAND, wish);
                            return ToolResult.ok("free time on; you will pick your own work in a moment");
                        }),
                metadata(START_FREE_TIME));
        registry.registerTool(
                callerTool(
                        END_FREE_TIME,
                        "End free time and come back to the owner. Use it when the owner calls you back or tells you to "
                                + "stop working; it is not the answer to 'don't follow me', which only switches following "
                                + "off.",
                        SchemaBuilder.object().build(),
                        (companion, caller, args) -> {
                            FreeTimeDriver driver = freeTimeDriver.apply(companion);
                            // "Come back" turns following on even when free time was already off: that is the whole
                            // order.
                            companion.setFollowMode(true);
                            if (!driver.state(companion.ownerUuid()).on()) {
                                return ToolResult.ok("free time was already off; following the owner again");
                            }
                            driver.stop(companion.ownerUuid(), "owner ended free time");
                            return ToolResult.ok("free time off; following the owner again");
                        }),
                metadata(END_FREE_TIME));
        registry.registerTool(
                callerTool(
                        CHOOSE_ACTIVITY,
                        "Free time only: choose the one activity you do next and the agenda need it serves. A planner "
                                + "turns it into steps.",
                        SchemaBuilder.object()
                                .string("goal", "The activity in one short sentence, like 'mine iron for a pickaxe'")
                                .array(
                                        "criteria",
                                        "How you will know it is done",
                                        SchemaBuilder.object()
                                                .string("text", "One check")
                                                .required("text")
                                                .build(),
                                        0,
                                        6)
                                .string("why", "One short sentence to tell the owner why")
                                .enumeration("need", "The agenda need this activity serves", agendaNames())
                                .integer("minutes", "How long it may take", 3, 120)
                                .required("goal", "why")
                                .build(),
                        (companion, caller, args) -> chooseActivity(companion, args)),
                metadata(CHOOSE_ACTIVITY));
    }

    private ToolResult chooseActivity(CompanionEntity companion, JsonObject args) {
        String goal = args.get("goal").getAsString().strip();
        if (goal.isEmpty() || goal.length() > 200) {
            return ToolResult.error("goal must be 1 to 200 characters");
        }
        String why = args.get("why").getAsString().strip();
        List<String> criteria = new ArrayList<>();
        if (args.has("criteria") && args.get("criteria").isJsonArray()) {
            for (JsonElement element : args.getAsJsonArray("criteria")) {
                criteria.add(
                        element.isJsonObject() && element.getAsJsonObject().has("text")
                                ? element.getAsJsonObject().get("text").getAsString()
                                : element.getAsString());
            }
        }
        int minutes = args.has("minutes") ? args.get("minutes").getAsInt() : 20;
        String need = args.has("need") && !args.get("need").isJsonNull()
                ? args.get("need").getAsString()
                : "";
        return freeTimeDriver
                .apply(companion)
                .choose(
                        companion,
                        goal,
                        criteria,
                        why.length() > FreeTime.MAX_WISH ? why.substring(0, FreeTime.MAX_WISH) : why,
                        minutes,
                        need);
    }

    private static String[] agendaNames() {
        NeedKind[] kinds = NeedKind.values();
        String[] names = new String[kinds.length];
        for (int i = 0; i < kinds.length; i++) {
            names[i] = kinds[i].name();
        }
        return names;
    }

    private static ToolMeta metadata(String name) {
        return switch (name) {
            case START_FREE_TIME ->
                new ToolMeta(
                        name,
                        ToolGroup.AUTONOMY,
                        false,
                        "- When the owner releases you (\"do your own thing\", \"do whatever you want\"), call start_free_time with their hint as wish; call end_free_time when they call you back.",
                        List.of(),
                        Cost.LOW);
            case END_FREE_TIME, CHOOSE_ACTIVITY ->
                new ToolMeta(
                        name,
                        ToolGroup.AUTONOMY,
                        false,
                        name.equals(CHOOSE_ACTIVITY)
                                ? "- In a free-time event, pick the single most useful activity from the agenda and call choose_activity once; the planner writes the steps."
                                : "",
                        List.of(),
                        Cost.LOW);
            default -> throw new IllegalArgumentException("No metadata declared for free-time tool: " + name);
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
