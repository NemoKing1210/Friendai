package dev.friedai.net;

import dev.friedai.core.BrainService;
import dev.friedai.core.FriedaiServerState;
import dev.friedai.debug.CompanionSnapshot;
import dev.friedai.debug.LogEntry;
import dev.friedai.debug.TaskRecord;
import dev.friedai.entity.CompanionEntity;
import dev.friedai.entity.CompanionManager;
import dev.friedai.perception.PerceptionBuilder;
import dev.friedai.task.Task;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

/** Builds what the menu shows. Log, perception and tool names are only included for operators. */
public final class SnapshotBuilder {
    /** At most this many log entries travel in one snapshot. */
    public static final int MAX_LOG_ENTRIES = 50;

    private SnapshotBuilder() {}

    public static CompanionSnapshot build(ServerPlayer player, long afterLogId) {
        var server = player.level().getServer();
        FriedaiServerState state = FriedaiServerState.get(server);
        BrainService brains = state.brains();
        UUID owner = player.getUUID();
        boolean operator = Operators.isOperator(player);
        Optional<CompanionEntity> companion = CompanionManager.get(server).find(owner);
        List<TaskRecord> history = brains.debug().historyOf(owner).recent();
        List<LogEntry> log = operator ? brains.debug().logOf(owner).after(afterLogId, MAX_LOG_ENTRIES) : List.of();
        List<String> toolNames = operator ? brains.toolNames() : List.of();
        if (companion.isEmpty()) {
            return new CompanionSnapshot(
                    false, operator, "", brains.activeProvider(), brains.activeModel(), false, "", history, log, "", toolNames);
        }
        CompanionEntity c = companion.get();
        return new CompanionSnapshot(
                true,
                operator,
                c.getName().getString(),
                brains.activeProvider(),
                brains.activeModel(),
                brains.brainOf(owner).map(b -> b.isThinking()).orElse(false),
                c.taskRunner().current().map(Task::describe).orElse("none"),
                history,
                log,
                operator ? PerceptionBuilder.detailed(c).toPromptText() : "",
                toolNames);
    }
}
