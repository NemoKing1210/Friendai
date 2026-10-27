package dev.friendai.tool.builtin;

import com.google.gson.JsonElement;
import dev.friendai.agent.autonomy.FreeTime;
import dev.friendai.agent.autonomy.NeedKind;
import dev.friendai.core.FreeTimeDriver;
import dev.friendai.core.FriendaiServerState;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.tool.AccessLevel;
import dev.friendai.tool.SchemaBuilder;
import dev.friendai.tool.ToolRegistry;
import dev.friendai.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;

/** Free time: the owner lets the companion work on its own, and the director picks what to do. */
final class FreeTimeTools {
    private FreeTimeTools() {}

    static void register(ToolRegistry<CompanionEntity> registry) {
        registry.register(ToolKit.callerTool("start_free_time",
                "Start free time: you work on your own, like a survival player, until the owner calls you back. "
                        + "Use it when the owner says to do your own thing or whatever you want. Pass their hint as wish.",
                SchemaBuilder.object().string("wish", "The owner's hint, like 'focus on food'; optional").build(),
                AccessLevel.OWNER, () -> true, (c, caller, args) -> {
                    FreeTimeDriver driver = driver(c);
                    if (driver.disabled()) {
                        return ToolResult.error("free time is disabled on this server");
                    }
                    String wish = args.has("wish") && !args.get("wish").isJsonNull()
                            ? args.get("wish").getAsString() : "";
                    driver.start(c.ownerUuid(), FreeTime.Origin.COMMAND, wish);
                    return ToolResult.ok("free time on; you will pick your own work in a moment");
                }));
        registry.register(ToolKit.callerTool("end_free_time",
                "End free time and go back to following the owner. Use it when the owner calls you back.",
                SchemaBuilder.object().build(),
                AccessLevel.OWNER, () -> true, (c, caller, args) -> {
                    FreeTimeDriver driver = driver(c);
                    if (!driver.state(c.ownerUuid()).on()) {
                        return ToolResult.ok("free time was already off");
                    }
                    driver.stop(c.ownerUuid(), "owner ended free time");
                    return ToolResult.ok("free time off");
                }));
        registry.register(ToolKit.callerTool("choose_activity",
                "Free time only: choose the one activity you do next and the agenda need it serves. A planner "
                        + "turns it into steps.",
                SchemaBuilder.object()
                        .string("goal", "The activity in one short sentence, like 'mine iron for a pickaxe'")
                        .array("criteria", "How you will know it is done", SchemaBuilder.object()
                                .string("text", "One check").required("text").build(), 0, 6)
                        .string("why", "One short sentence to tell the owner why")
                        .enumeration("need", "The agenda need this activity serves", agendaNames())
                        .integer("minutes", "How long it may take", 3, 120)
                        .required("goal", "why")
                        .build(),
                AccessLevel.OWNER, () -> true, (c, caller, args) -> {
                    String goal = args.get("goal").getAsString().strip();
                    if (goal.isEmpty() || goal.length() > 200) {
                        return ToolResult.error("goal must be 1 to 200 characters");
                    }
                    String why = args.get("why").getAsString().strip();
                    List<String> criteria = new ArrayList<>();
                    if (args.has("criteria") && args.get("criteria").isJsonArray()) {
                        for (JsonElement e : args.getAsJsonArray("criteria")) {
                            criteria.add(e.isJsonObject() && e.getAsJsonObject().has("text")
                                    ? e.getAsJsonObject().get("text").getAsString()
                                    : e.getAsString());
                        }
                    }
                    int minutes = args.has("minutes") ? args.get("minutes").getAsInt() : 20;
                    String need = args.has("need") && !args.get("need").isJsonNull()
                            ? args.get("need").getAsString() : "";
                    return driver(c).choose(c, goal, criteria,
                            why.length() > FreeTime.MAX_WISH ? why.substring(0, FreeTime.MAX_WISH) : why, minutes,
                            need);
                }));
    }

    private static String[] agendaNames() {
        NeedKind[] kinds = NeedKind.values();
        String[] names = new String[kinds.length];
        for (int i = 0; i < kinds.length; i++) {
            names[i] = kinds[i].name();
        }
        return names;
    }

    private static FreeTimeDriver driver(CompanionEntity c) {
        return FriendaiServerState.get(c.level().getServer()).brains().freeTime();
    }
}
