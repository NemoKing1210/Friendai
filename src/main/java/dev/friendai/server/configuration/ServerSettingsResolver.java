package dev.friendai.server.configuration;

import dev.friendai.execution.settings.TaskRuntimeSettings;
import dev.friendai.features.navigation.pathing.BlockList;
import dev.friendai.features.navigation.pathing.PathingConfig;
import dev.friendai.features.trading.TradingRuntimeSettings;
import dev.friendai.infrastructure.config.ServerConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Resolves mutable config DTOs into one immutable runtime snapshot. */
public final class ServerSettingsResolver {

    private ServerSettingsResolver() {}

    public static ServerSettings resolve(ServerConfig config, Consumer<String> unknownBreakEntry) {
        ServerConfig.TasksSection taskConfig = config.tasks;
        TaskRuntimeSettings tasks = new TaskRuntimeSettings(
                taskConfig.taskTimeoutSec,
                taskConfig.queueMaxJobs,
                taskConfig.queueMaxPlans,
                taskConfig.queueBlockedNoticeSec,
                taskConfig.queueMaxStepsPerAdd,
                taskConfig.defaultFollow,
                taskConfig.mineSearchRadius,
                taskConfig.treeSearchRadius,
                taskConfig.fellCollectDrops,
                taskConfig.mineCollectDrops,
                taskConfig.treePerception,
                taskConfig.treePerceptionRadius,
                config.structures.maxNearbySpawned,
                taskConfig.freeTimeWatchdogSec);

        PathingConfig pathing = config.pathing.toPathingConfig();
        BlockList breakList = BlockList.parse(config.pathing.breakBlacklist, unknownBreakEntry);
        List<String> placeEntries = new ArrayList<>(config.pathing.breakBlacklist);
        if (config.pathing.placeBlacklist != null) placeEntries.addAll(config.pathing.placeBlacklist);
        BlockList placeList = BlockList.parse(placeEntries, ignored -> {});

        ServerConfig.TradingSection tradeConfig = taskConfig.trading;
        TradingRuntimeSettings trading = new TradingRuntimeSettings(
                tradeConfig.enabled,
                tradeConfig.idleRefresh,
                tradeConfig.observationRadius,
                tradeConfig.quoteFreshTicks,
                tradeConfig.locationFreshTicks,
                tradeConfig.idleRefreshIntervalTicks,
                tradeConfig.waitTicks,
                tradeConfig.maxWaitTicks,
                tradeConfig.maxBarterIngots,
                tradeConfig.barterDefaultIngots);

        return new ServerSettings(tasks, pathing, breakList, placeList, config.autonomy.toSettings(), trading);
    }
}
