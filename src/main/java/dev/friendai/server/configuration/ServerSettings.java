package dev.friendai.server.configuration;

import dev.friendai.agent.autonomy.AutonomySettings;
import dev.friendai.execution.queue.QueueLimits;
import dev.friendai.execution.settings.TaskRuntimeSettings;
import dev.friendai.features.navigation.pathing.BlockList;
import dev.friendai.features.navigation.pathing.PathingConfig;
import dev.friendai.features.trading.TradingRuntimeSettings;

/** Coherent immutable view of the settings consumed by one server runtime. */
public record ServerSettings(
        TaskRuntimeSettings tasks,
        PathingConfig pathing,
        BlockList breakList,
        BlockList placeList,
        AutonomySettings autonomy,
        TradingRuntimeSettings trading,
        int autoMineMinBlocks) {

    public static final ServerSettings DEFAULTS = defaults();

    public ServerSettings {
        tasks = tasks == null ? TaskRuntimeSettings.DEFAULTS : tasks;
        pathing = pathing == null ? PathingConfig.DEFAULTS : pathing;
        breakList = breakList == null ? BlockList.parse(pathing.breakBlacklist(), ignored -> {}) : breakList;
        placeList = placeList == null ? BlockList.parse(pathing.placeBlacklist(), ignored -> {}) : placeList;
        autonomy = autonomy == null ? AutonomySettings.DEFAULTS : autonomy;
        trading = trading == null ? TradingRuntimeSettings.DEFAULTS : trading;
        autoMineMinBlocks = autoMineMinBlocks < 1 ? 16 : autoMineMinBlocks;
    }

    public ServerSettings(
            TaskRuntimeSettings tasks,
            PathingConfig pathing,
            BlockList breakList,
            BlockList placeList,
            AutonomySettings autonomy,
            TradingRuntimeSettings trading) {
        this(tasks, pathing, breakList, placeList, autonomy, trading, 16);
    }

    public static ServerSettings defaults() {
        PathingConfig pathing = PathingConfig.DEFAULTS;
        java.util.ArrayList<String> placeEntries = new java.util.ArrayList<>(pathing.breakBlacklist());
        placeEntries.addAll(pathing.placeBlacklist());
        return new ServerSettings(
                TaskRuntimeSettings.DEFAULTS,
                pathing,
                BlockList.parse(pathing.breakBlacklist(), ignored -> {}),
                BlockList.parse(placeEntries, ignored -> {}),
                AutonomySettings.DEFAULTS,
                TradingRuntimeSettings.DEFAULTS);
    }

    public QueueLimits queueLimits() {
        return new QueueLimits(tasks.queueMaxJobs(), tasks.queueMaxPlans(), tasks.queueMaxStepsPerAdd(), 8192, 5);
    }
}
