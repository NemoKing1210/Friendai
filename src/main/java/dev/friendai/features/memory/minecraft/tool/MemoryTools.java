package dev.friendai.features.memory.minecraft.tool;

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
import dev.friendai.features.memory.MemoryActions;
import dev.friendai.server.execution.tools.FeatureToolContributor;
import dev.friendai.server.execution.tools.FeatureToolRegistry;
import java.util.List;
import java.util.Objects;

/** Remembering facts: remember, forget and recall. All three are available to every role; the policy decides. */
public final class MemoryTools implements FeatureToolContributor {
    private final MemoryAccess memory;

    public MemoryTools(MemoryAccess memory) {
        this.memory = Objects.requireNonNull(memory);
    }

    @Override
    public void register(FeatureToolRegistry registry) {
        registry.registerTool(
                callerTool(
                        "remember",
                        "Store a durable fact: a name, a preference, a place with coordinates, a promise. Not for small talk.",
                        SchemaBuilder.object()
                                .string("subject", "'me' (the current speaker), 'owner', 'world', or a player name")
                                .enumeration(
                                        "category",
                                        "Fact category",
                                        "PROFILE",
                                        "PREFERENCE",
                                        "PLACE",
                                        "PROMISE",
                                        "NOTE")
                                .string("text", "One short sentence")
                                .required("subject", "category", "text")
                                .build(),
                        memory,
                        (actions, args) -> actions.remember(
                                args.get("subject").getAsString(),
                                args.get("category").getAsString(),
                                args.get("text").getAsString())),
                metadata("remember"));
        registry.registerTool(
                callerTool(
                        "forget",
                        "Delete a remembered fact by its id.",
                        SchemaBuilder.object()
                                .integer("id", "Fact id", 1, Integer.MAX_VALUE)
                                .required("id")
                                .build(),
                        memory,
                        (actions, args) -> actions.forget(args.get("id").getAsInt())),
                metadata("forget"));
        registry.registerTool(
                callerTool(
                        "recall",
                        "List what you remember about a player who is not shown in your memory block. Narrow it with a "
                                + "text fragment or a category when the list is long.",
                        SchemaBuilder.object()
                                .string("player", "Player name, or 'me'")
                                .string("query", "Only facts containing this text (case-insensitive)")
                                .enumeration(
                                        "category",
                                        "Only facts of this category",
                                        "PROFILE",
                                        "PREFERENCE",
                                        "PLACE",
                                        "PROMISE",
                                        "NOTE")
                                .required("player")
                                .build(),
                        memory,
                        (actions, args) -> actions.recall(
                                args.get("player").getAsString(), optional(args, "query"), optional(args, "category"))),
                metadata("recall"));
    }

    private static Tool<CompanionEntity> callerTool(
            String name, String description, JsonObject schema, MemoryAccess memory, Action action) {
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
                return AccessLevel.GUEST;
            }

            @Override
            public boolean enabled() {
                return memory.enabled();
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
                    return ToolResult.ok(action.run(memory.actions(companion, caller), args));
                } catch (IllegalArgumentException e) {
                    return ToolResult.error(e.getMessage());
                }
            }
        };
    }

    private static String optional(JsonObject args, String name) {
        return args.has(name) && !args.get(name).isJsonNull() ? args.get(name).getAsString() : "";
    }

    private static ToolMeta metadata(String name) {
        return new ToolMeta(name, ToolGroup.MEMORY, true, "", List.of(), Cost.LOW);
    }

    @FunctionalInterface
    private interface Action {
        String run(MemoryActions actions, JsonObject args);
    }
}
