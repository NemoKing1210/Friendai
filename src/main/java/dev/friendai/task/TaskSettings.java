package dev.friendai.task;

import dev.friendai.pathing.BlockList;
import dev.friendai.pathing.PathingConfig;
import dev.friendai.queue.QueueLimits;

/** Live task limits, updated whenever the server config is applied. */
public final class TaskSettings {
    public static volatile int taskTimeoutSec = 300;
    public static volatile int queueMaxJobs = 64;
    public static volatile int queueMaxPlans = 8;
    public static volatile int queueBlockedNoticeSec = 30;
    public static volatile int queueMaxStepsPerAdd = 32;
    public static volatile boolean defaultFollow = true;
    /** How long a stay order lasts before it ends by itself; follows {@code tasks.taskTimeoutSec}. */
    public static volatile int stayTimeoutSec = 300;
    /** How long free time may stand with nothing running before the driver forces a decision. */
    public static volatile int freeTimeWatchdogSec = 120;
    public static volatile int mineSearchRadius = 32;
    public static volatile int treeSearchRadius = 32;
    public static volatile boolean fellCollectDrops = true;
    public static volatile boolean mineCollectDrops = true;
    public static volatile boolean treePerception = true;
    public static volatile int treePerceptionRadius = 12;
    /** How many golems of one kind may stand around a structure before building another is refused. */
    public static volatile int maxNearbySpawned = 4;
    public static volatile PathingConfig pathing = PathingConfig.DEFAULTS;
    /** Block ids and tags pathing never breaks; parsed once per applied config. */
    public static volatile BlockList breakList =
            BlockList.parse(PathingConfig.DEFAULTS.breakBlacklist(), entry -> {});
    /** Block ids and tags pathing never places: the break list plus the place additions. */
    public static volatile BlockList placeList =
            BlockList.parse(PathingConfig.DEFAULTS.placeBlacklist(), entry -> {});

    private TaskSettings() {}

    public static QueueLimits queueLimits() {
        return new QueueLimits(queueMaxJobs, queueMaxPlans, queueMaxStepsPerAdd, 8192, 5);
    }

    public static int queueBlockedNoticeTicks() { return queueBlockedNoticeSec * 20; }
}
