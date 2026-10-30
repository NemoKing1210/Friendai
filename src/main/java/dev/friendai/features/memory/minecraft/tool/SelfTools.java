package dev.friendai.features.memory.minecraft.tool;

import com.google.gson.JsonObject;
import dev.friendai.agent.tools.Cost;
import dev.friendai.agent.tools.ToolGroup;
import dev.friendai.agent.tools.ToolMeta;
import dev.friendai.agent.turn.LogActions;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.SchemaBuilder;
import dev.friendai.execution.tool.Tool;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.features.memory.transcript.TranscriptActions;
import dev.friendai.foundation.diagnostics.LogKind;
import dev.friendai.server.execution.tools.FeatureToolContributor;
import dev.friendai.server.execution.tools.FeatureToolRegistry;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Tools that let the model read its own records: recent activity and conversations. */
public final class SelfTools implements FeatureToolContributor {
    private final SelfAccess self;

    public SelfTools(SelfAccess self) {
        this.self = Objects.requireNonNull(self);
    }

    @Override
    public void register(FeatureToolRegistry registry) {
        registry.registerTool(
                callerTool(
                        "read_log",
                        "Read your own recent activity: what you were told, what you answered, which tools you called and "
                                + "what they returned, errors and task events. Ids let you continue from the last line. "
                                + "Use it after a failure or when the owner asks what happened.",
                        SchemaBuilder.object()
                                .enumeration(
                                        "kind",
                                        "Only entries of this kind",
                                        "ALL",
                                        "USER",
                                        "ASSISTANT",
                                        "TOOL_CALL",
                                        "TOOL_RESULT",
                                        "ERROR",
                                        "TASK",
                                        "MANUAL")
                                .integer("since", "Only entries with an id greater than this", 0, Integer.MAX_VALUE)
                                .integer("before", "Only entries with an id smaller than this", 0, Integer.MAX_VALUE)
                                .integer("limit", "How many entries to return", 1, 200)
                                .enumeration("order", "Newest or oldest first", "newest", "oldest")
                                .build(),
                        AccessLevel.OWNER,
                        this::readLog),
                meta("read_log"));
        registry.registerTool(
                callerTool(
                        "recent_chat",
                        "Read the last messages you exchanged with a player, your own replies included, with ids and "
                                + "relative times. Use it when the owner asks what you talked about or what was agreed.",
                        SchemaBuilder.object()
                                .string("player", "Player name, 'me' (the current speaker) or 'owner'")
                                .integer("limit", "How many lines to return", 1, 50)
                                .integer("minutes", "Only lines from the last N minutes", 1, 10_080)
                                .integer("before", "Only lines with an id smaller than this", 0, Integer.MAX_VALUE)
                                .build(),
                        AccessLevel.TRUSTED,
                        this::recentChat),
                meta("recent_chat"));
    }

    /** Guidance on when to look lives in the role-aware prompt section, so a guest never sees an owner-only tool. */
    private static ToolMeta meta(String name) {
        return new ToolMeta(name, ToolGroup.MEMORY, true, "", List.of(), Cost.LOW);
    }

    private ToolResult readLog(CompanionEntity companion, Caller caller, JsonObject args) {
        UUID owner = self.owner(companion);
        Set<LogKind> kinds = kinds(args);
        long since = args.has("since") ? args.get("since").getAsLong() : 0;
        long before = args.has("before") ? args.get("before").getAsLong() : 0;
        int limit = args.has("limit") ? args.get("limit").getAsInt() : 40;
        boolean newest = !args.has("order") || args.get("order").getAsString().equalsIgnoreCase("newest");
        return ToolResult.ok(LogActions.read(
                caller,
                owner,
                self.log(owner),
                kinds,
                since,
                before,
                limit,
                newest,
                self.resultBudget(),
                self.redactor()));
    }

    private ToolResult recentChat(CompanionEntity companion, Caller caller, JsonObject args) {
        UUID owner = self.owner(companion);
        if (owner == null) return ToolResult.error("The companion has no owner to read a conversation for.");
        String player = args.has("player") && !args.get("player").isJsonNull()
                ? args.get("player").getAsString()
                : "me";
        int limit = args.has("limit") ? args.get("limit").getAsInt() : 20;
        int minutes = args.has("minutes") ? args.get("minutes").getAsInt() : 0;
        long before = args.has("before") ? args.get("before").getAsLong() : 0;
        return ToolResult.ok(TranscriptActions.recent(
                self.transcript(owner),
                owner,
                caller,
                player,
                limit,
                minutes,
                before,
                self.now(),
                self.resultBudget(),
                self.transcriptLimits(),
                self.redactor()));
    }

    private static Set<LogKind> kinds(JsonObject args) {
        if (!args.has("kind") || args.get("kind").isJsonNull()) return Set.of();
        String value = args.get("kind").getAsString();
        if (value.equalsIgnoreCase("ALL")) return Set.of();
        try {
            return EnumSet.of(LogKind.valueOf(value.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown log kind " + value + ".");
        }
    }

    private Tool<CompanionEntity> callerTool(
            String name, String description, JsonObject schema, AccessLevel min, Action action) {
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
                return min;
            }

            @Override
            public boolean enabled() {
                return self.enabled();
            }

            @Override
            public ToolResult execute(CompanionEntity companion, JsonObject args) {
                return run(companion, new Caller(null, "", AccessLevel.GUEST), args);
            }

            @Override
            public ToolResult execute(CompanionEntity companion, Caller caller, JsonObject args) {
                return run(companion, caller, args);
            }

            private ToolResult run(CompanionEntity companion, Caller caller, JsonObject args) {
                try {
                    return action.run(companion, caller, args);
                } catch (IllegalArgumentException e) {
                    return ToolResult.error(e.getMessage());
                }
            }
        };
    }

    @FunctionalInterface
    private interface Action {
        ToolResult run(CompanionEntity companion, Caller caller, JsonObject args);
    }
}
