package dev.friendai.server.net;

import dev.friendai.FriendaiMod;
import dev.friendai.agent.goal.Criterion;
import dev.friendai.agent.goal.Goal;
import dev.friendai.agent.goal.GoalBoard;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.history.TaskRecord;
import dev.friendai.foundation.diagnostics.LogEntry;
import dev.friendai.presentation.companion.CompanionSnapshot;
import dev.friendai.presentation.companion.CompanionVitals;
import dev.friendai.server.agent.CompanionSessions;
import dev.friendai.server.agent.perception.PerceptionBuilder;
import dev.friendai.server.agent.perception.PerceptionContext;
import dev.friendai.server.runtime.ServerRuntime;
import dev.friendai.task.queue.CompanionGuardFacts;
import java.util.List;
import java.util.Locale;
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
        Feeling feeling = feelingOf(runtime, owner, c);
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
                status.plannerModel(),
                feeling.bondTier(),
                feeling.bondReasons(),
                feeling.goalText(),
                feeling.goalCriteria(),
                feeling.goalUnmet(),
                companion
                        .map(entity -> entity.executionCoordinator().manualPause())
                        .orElse(false));
    }

    /** What the companion feels and is working on, already in the shape the snapshot wants. */
    private record Feeling(
            String bondTier,
            List<String> bondReasons,
            String goalText,
            List<String> goalCriteria,
            List<String> goalUnmet) {}

    /**
     * The owner's own bond and the primary goal, read once per snapshot. Every part is empty rather than thrown when
     * the read is impossible: a broken save or an unloaded companion must not cost the owner the whole menu.
     */
    private static Feeling feelingOf(ServerRuntime runtime, UUID owner, CompanionEntity companion) {
        String tier = "";
        List<String> reasons = List.of();
        try {
            tier = companion.bondTier().name().toLowerCase(Locale.ROOT);
            reasons = companion.bondReasons();
        } catch (RuntimeException failure) {
            FriendaiMod.LOGGER.debug("Bond read for the menu failed for {}", owner, failure);
        }
        String goalText = "";
        List<String> criteria = List.of();
        List<String> unmet = List.of();
        try {
            GoalBoard board = runtime.goals().board(owner);
            Optional<Goal> primary = board.primary();
            if (primary.isPresent()) {
                Goal goal = primary.get();
                goalText = goal.text();
                criteria = goal.criteria().stream().map(Criterion::text).toList();
                CompanionGuardFacts facts = new CompanionGuardFacts(companion);
                facts.refresh();
                unmet = board.unmet(goal.id(), facts).stream()
                        .map(Criterion::text)
                        .toList();
            }
        } catch (RuntimeException failure) {
            FriendaiMod.LOGGER.debug("Goal read for the menu failed for {}", owner, failure);
        }
        return new Feeling(tier, reasons, goalText, criteria, unmet);
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
                c.combatSettings().protection().label(),
                c.combatSettings().style().label(),
                c.combatController().activeAction(),
                c.combatController().activeReason(),
                c.paceMode().label(),
                c.experience().level(),
                c.experience().progress());
    }
}
