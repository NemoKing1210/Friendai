package dev.friendai.test;

import com.google.gson.JsonObject;
import dev.friendai.core.FriendaiServerState;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.ToolRegistry;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.features.memory.minecraft.tool.MemoryAccess;
import dev.friendai.server.agent.CompanionSessions;
import dev.friendai.server.agent.perception.PerceptionContext;
import dev.friendai.server.agent.tools.FriendaiTools;
import dev.friendai.server.agent.tools.ToolDependencies;
import dev.friendai.server.execution.tools.QueueTools;
import dev.friendai.server.execution.tools.TaskToolDefinition;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntSupplier;

/** Builds a test registry with capabilities from the GameTest's active server. */
final class GameTestToolRegistry {
    private GameTestToolRegistry() {}

    static ToolRegistry<CompanionEntity> create(CompanionEntity companion, IntSupplier radius) {
        return create(companion, radius, MemoryAccess.DISABLED);
    }

    static ToolRegistry<CompanionEntity> create(CompanionEntity companion, IntSupplier radius, MemoryAccess memory) {
        FriendaiServerState state =
                GameTestSupport.serverState(companion.level().getServer());
        CompanionSessions sessions = state.sessions();
        ToolDependencies dependencies = new ToolDependencies(
                memory,
                state.profiles(),
                ignored -> new PerceptionContext(state.resources(), state.settings(), state.freeTime()),
                ignored -> state.resources(),
                state,
                sessions,
                sessions,
                ignored -> sessions,
                ignored -> state,
                ignored -> sessions.freeTime(),
                candidate -> searchHintLine(state, candidate),
                state.placeService(),
                state.placedService());
        return FriendaiTools.build(
                        radius,
                        radius,
                        dependencies,
                        () -> state.settings().tasks().mineCollectDrops(),
                        () -> state.settings().tasks().fellCollectDrops())
                .registry();
    }

    static ToolResult addPlan(
            CompanionEntity companion,
            Caller caller,
            JsonObject args,
            Map<String, TaskToolDefinition> definitions,
            boolean linkGoal,
            Set<String> denied) {
        var access = GameTestSupport.serverState(companion.level().getServer()).sessions();
        return QueueTools.addPlan(access, companion, caller, args, definitions, linkGoal, denied);
    }

    private static String searchHintLine(FriendaiServerState state, CompanionEntity companion) {
        UUID owner = companion.ownerUuid();
        if (owner == null || companion.level().getServer() == null) return "";
        long now = companion.level().getServer().overworld().getGameTime();
        return state.searchHints()
                .get(owner, now)
                .map(hint -> "\nsearch hint: " + hint.describe(now))
                .orElse("\nsearch hint: none");
    }
}
