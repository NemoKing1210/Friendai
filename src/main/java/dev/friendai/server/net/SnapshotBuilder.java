package dev.friendai.server.net;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.history.TaskRecord;
import dev.friendai.foundation.diagnostics.LogEntry;
import dev.friendai.presentation.companion.CompanionSnapshot;
import dev.friendai.presentation.companion.CompanionVitals;
import dev.friendai.server.agent.CompanionSessions;
import dev.friendai.server.agent.perception.PerceptionBuilder;
import dev.friendai.server.agent.perception.PerceptionContext;
import dev.friendai.server.runtime.ServerRuntime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

/** Builds what the menu shows. Log, perception and tool names are only included for operators. */
public final class SnapshotBuilder {
    /** At most this many log entries travel in one snapshot. */
    public static final int MAX_LOG_ENTRIES = 50;

    private SnapshotBuilder() {}

    public static CompanionSnapshot build(ServerRuntime runtime, ServerPlayer player, long afterLogId) {
        var server = runtime.server();
        CompanionSessions brains = runtime.sessions();
        UUID owner = player.getUUID();
        boolean operator = Operators.isOperator(player);
        Optional<CompanionEntity> companion = runtime.companions().find(owner);
        List<TaskRecord> history = brains.debug().historyOf(owner).recent();
        List<LogEntry> log = operator ? brains.debug().logOf(owner).after(afterLogId, MAX_LOG_ENTRIES) : List.of();
        List<String> toolNames = operator ? brains.toolNames() : List.of();
        boolean autoRespawn = runtime.companionLife().autoRespawn(owner) && !server.isHardcore();
        boolean creativeMode = runtime.companionLife().creativeMode(owner);
        boolean creativeModeAvailable = creativeMode || operator;
        boolean followMode =
                runtime.companionLife().followModeChoice(owner).orElse(runtime.config().tasks.defaultFollow);
        var freeTime = runtime.freeTime().state(owner);
        var status = brains.freeTime().status(owner);
        if (companion.isEmpty()) {
            return new CompanionSnapshot(
                    false,
                    operator,
                    "",
                    brains.activeProvider(),
                    brains.activeModel(),
                    false,
                    "",
                    history,
                    log,
                    "",
                    toolNames,
                    CompanionVitals.NONE,
                    List.of(),
                    autoRespawn,
                    false,
                    creativeMode,
                    false,
                    false,
                    "",
                    false,
                    followMode,
                    status.next(),
                    status.budget(),
                    status.last(),
                    status.plannerModel());
        }
        CompanionEntity c = companion.get();
        return new CompanionSnapshot(
                true,
                operator,
                c.getName().getString(),
                brains.activeProvider(),
                brains.activeModel(),
                brains.brainOf(owner).map(b -> b.isThinking()).orElse(false),
                c.executionCoordinator().queueSummary(),
                history,
                log,
                operator
                        ? PerceptionBuilder.detailed(
                                        c,
                                        new PerceptionContext(
                                                runtime.resourceCandidates(), runtime.settings(), runtime.freeTime()))
                                .toPromptText()
                        : "",
                toolNames,
                vitalsOf(c),
                c.executionCoordinator().queueRows(),
                autoRespawn,
                !server.isHardcore(),
                c.creativeMode(),
                creativeModeAvailable,
                freeTime.on(),
                freeTime.shift() == null ? "" : freeTime.shift().goal(),
                freeTime.awaitingChoice(),
                c.followMode(),
                status.next(),
                status.budget(),
                status.last(),
                status.plannerModel());
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
