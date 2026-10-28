package dev.friendai.task;

import dev.friendai.core.CreativeModeService;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.settings.TaskRuntimeSettings;
import dev.friendai.features.creative.CreativeAction;
import dev.friendai.features.creative.CreativeActionContext;
import dev.friendai.features.navigation.pathing.BlockList;
import dev.friendai.features.navigation.pathing.PathingConfig;
import dev.friendai.server.configuration.ServerSettings;
import dev.friendai.server.execution.ExecutionCoordinator;
import java.util.List;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public record TaskContext(
        CompanionEntity companion,
        ServerLevel level,
        ExecutionCoordinator execution,
        CreativeActionContext creativeActionContext,
        ServerSettings serverSettings) {
    public TaskContext(
            CompanionEntity companion,
            ServerLevel level,
            ExecutionCoordinator execution,
            CreativeActionContext creativeActionContext) {
        this(companion, level, execution, creativeActionContext, companion.serverSettings());
    }

    public TaskContext(CompanionEntity companion, ServerLevel level, ExecutionCoordinator execution) {
        this(companion, level, execution, null, companion.serverSettings());
    }

    /** The context of a task it ticks itself, for tests and for tasks that run others. */
    public TaskContext(CompanionEntity companion, ServerLevel level) {
        this(companion, level, companion.executionCoordinator(), null, companion.serverSettings());
    }

    public TaskRuntimeSettings settings() {
        return serverSettings.tasks();
    }

    public PathingConfig pathingConfig() {
        return serverSettings.pathing();
    }

    public BlockList breakList() {
        return serverSettings.breakList();
    }

    public BlockList placeList() {
        return serverSettings.placeList();
    }

    public int autoMineMinBlocks() {
        return serverSettings.autoMineMinBlocks();
    }

    public void addBlockExhaustion() {
        companion.hunger().addExhaustion(CompanionEntity.EXHAUSTION_BLOCK);
    }

    public List<String> keepItems() {
        return serverSettings.autonomy().keepItems();
    }

    public Optional<ServerPlayer> owner() {
        return companion.owner();
    }

    public boolean canUseCreativeAction(CreativeAction action) {
        return CreativeModeService.isActionAuthorized(companion, creativeActionContext, action);
    }

    public boolean flightAuthorized() {
        return canUseCreativeAction(CreativeAction.FLIGHT);
    }

    public boolean passiveFlightAllowed() {
        return CreativeModeService.ownerMayUsePassiveFlight(companion);
    }

    public boolean defaultActivity() {
        return execution != null && execution.currentIsDefaultActivity();
    }

    public void debug(String text) {
        if (execution != null) execution.debug(text);
    }

    /** The running task belongs to free time, which follows stricter rules than an owner order. */
    public boolean autonomous() {
        return execution != null && execution.autonomousActive();
    }
}
