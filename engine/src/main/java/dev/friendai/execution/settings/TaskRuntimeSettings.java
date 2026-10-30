package dev.friendai.execution.settings;

/** Immutable task-facing settings copied from one server configuration snapshot. */
public record TaskRuntimeSettings(
        int taskTimeoutSec,
        int placeIntervalTicks,
        int queueMaxJobs,
        int queueMaxPlans,
        int queueBlockedNoticeSec,
        int queueMaxStepsPerAdd,
        boolean defaultFollow,
        int mineSearchRadius,
        int treeSearchRadius,
        boolean fellCollectDrops,
        boolean mineCollectDrops,
        boolean treePerception,
        int treePerceptionRadius,
        int maxNearbySpawned,
        int freeTimeWatchdogSec) {

    public static final TaskRuntimeSettings DEFAULTS =
            new TaskRuntimeSettings(300, 4, 64, 8, 30, 32, true, 32, 32, true, true, true, 12, 4, 120);
}
