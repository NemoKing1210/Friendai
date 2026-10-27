package dev.friendai.net;

import dev.friendai.core.BrainService;
import dev.friendai.core.FriendaiServerState;
import dev.friendai.debug.CompanionSnapshot;
import dev.friendai.debug.CompanionVitals;
import dev.friendai.debug.LogEntry;
import dev.friendai.debug.TaskRecord;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.perception.PerceptionBuilder;
import dev.friendai.task.Task;
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
        FriendaiServerState state = FriendaiServerState.get(server);
        BrainService brains = state.brains();
        UUID owner = player.getUUID();
        boolean operator = Operators.isOperator(player);
        Optional<CompanionEntity> companion = CompanionManager.get(server).find(owner);
        List<TaskRecord> history = brains.debug().historyOf(owner).recent();
        List<LogEntry> log = operator ? brains.debug().logOf(owner).after(afterLogId, MAX_LOG_ENTRIES) : List.of();
        List<String> toolNames = operator ? brains.toolNames() : List.of();
        boolean autoRespawn = state.companionLife().autoRespawn(owner) && !server.isHardcore();
        boolean creativeMode = state.companionLife().creativeMode(owner);
        boolean creativeModeAvailable = creativeMode || operator;
        boolean followMode = state.companionLife().followModeChoice(owner)
                .orElse(state.config().tasks.defaultFollow);
        if (companion.isEmpty()) {
            return new CompanionSnapshot(
                    false, operator, "", brains.activeProvider(), brains.activeModel(), false, "", history, log, "",
                    toolNames, CompanionVitals.NONE, List.of(), autoRespawn, false, creativeMode, false, false, "",
                    false, followMode);
        }
        CompanionEntity c = companion.get();
        var freeTime = state.freeTime().state(owner);
        return new CompanionSnapshot(
                true,
                operator,
                c.getName().getString(),
                brains.activeProvider(),
                brains.activeModel(),
                brains.brainOf(owner).map(b -> b.isThinking()).orElse(false),
                c.taskRunner().queueSummary(),
                history,
                log,
                operator ? PerceptionBuilder.detailed(c).toPromptText() : "",
                toolNames,
                vitalsOf(c),
                c.taskRunner().queueRows(),
                autoRespawn,
                !server.isHardcore(),
                c.creativeMode(),
                creativeModeAvailable,
                freeTime.on(),
                freeTime.shift() == null ? "" : freeTime.shift().goal(),
                freeTime.awaitingChoice(),
                c.followMode());
    }

    private static CompanionVitals vitalsOf(CompanionEntity c) {
        int used = 0;
        for (int i = 0; i < c.inventory().getContainerSize(); i++) {
            if (!c.inventory().getItem(i).isEmpty()) {
                used++;
            }
        }
        return new CompanionVitals(
                c.getHealth(),
                c.getMaxHealth(),
                c.hunger().food(),
                used,
                c.inventory().getContainerSize(),
                c.combatMode().name().toLowerCase(java.util.Locale.ROOT),
                c.combatSettings().protection().label(),
                c.combatSettings().style().label(),
                c.combatController().activeAction(),
                c.combatController().activeReason(),
                c.paceMode().label(),
                c.experience().level(),
                c.experience().progress());
    }
}
