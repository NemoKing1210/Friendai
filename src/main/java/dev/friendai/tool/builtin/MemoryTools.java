package dev.friendai.tool.builtin;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.SchemaBuilder;
import dev.friendai.execution.tool.ToolRegistry;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.memory.MemoryActions;
import dev.friendai.memory.PlayerDirectory;
import dev.friendai.memory.PlayerRef;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Remembering facts: remember, forget and recall. All three are available to every role; the policy decides. */
final class MemoryTools {
    private MemoryTools() {}

    @FunctionalInterface
    private interface Action {
        String run(MemoryActions actions);
    }

    static void register(ToolRegistry<CompanionEntity> registry, MemoryAccess memory) {
        registry.register(ToolKit.callerTool(
                "remember",
                "Store a durable fact: a name, a preference, a place with coordinates, a promise. Not for small talk.",
                SchemaBuilder.object()
                        .string("subject", "'me' (the current speaker), 'owner', 'world', or a player name")
                        .enumeration("category", "Fact category", "PROFILE", "PREFERENCE", "PLACE", "PROMISE", "NOTE")
                        .string("text", "One short sentence")
                        .required("subject", "category", "text")
                        .build(),
                AccessLevel.GUEST,
                memory::enabled,
                (companion, caller, args) -> run(
                        memory,
                        companion,
                        caller,
                        actions -> actions.remember(
                                args.get("subject").getAsString(),
                                args.get("category").getAsString(),
                                args.get("text").getAsString()))));

        registry.register(ToolKit.callerTool(
                "forget",
                "Delete a remembered fact by its id.",
                SchemaBuilder.object()
                        .integer("id", "Fact id", 1, Integer.MAX_VALUE)
                        .required("id")
                        .build(),
                AccessLevel.GUEST,
                memory::enabled,
                (companion, caller, args) -> run(
                        memory,
                        companion,
                        caller,
                        actions -> actions.forget(args.get("id").getAsInt()))));

        registry.register(ToolKit.callerTool(
                "recall",
                "List what you remember about a player who is not shown in your memory block.",
                SchemaBuilder.object()
                        .string("player", "Player name, or 'me'")
                        .required("player")
                        .build(),
                AccessLevel.GUEST,
                memory::enabled,
                (companion, caller, args) -> run(
                        memory,
                        companion,
                        caller,
                        actions -> actions.recall(args.get("player").getAsString()))));
    }

    private static ToolResult run(MemoryAccess memory, CompanionEntity companion, Caller caller, Action action) {
        try {
            return ToolResult.ok(action.run(actions(memory, companion, caller)));
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
    }

    private static MemoryActions actions(MemoryAccess memory, CompanionEntity companion, Caller caller) {
        UUID owner = companion.ownerUuid();
        return new MemoryActions(
                memory.book(owner), owner, caller, memory.limits(), System.currentTimeMillis(), directory(companion));
    }

    private static PlayerDirectory directory(CompanionEntity companion) {
        return name -> {
            if (!(companion.level() instanceof ServerLevel level)) {
                return Optional.empty();
            }
            MinecraftServer server = level.getServer();
            if (server == null) {
                return Optional.empty();
            }
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                String playerName = player.getName().getString();
                if (playerName != null && playerName.equalsIgnoreCase(name)) {
                    return Optional.of(new PlayerRef(player.getUUID(), playerName));
                }
            }
            return Optional.empty();
        };
    }
}
