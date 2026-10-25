package dev.friendai.task;

import dev.friendai.pathing.BlockList;
import dev.friendai.pathing.PathingConfig;

/** Live task limits, updated whenever the server config is applied. */
public final class TaskSettings {
    public static volatile int taskTimeoutSec = 300;
    public static volatile boolean defaultFollow = true;
    public static volatile int mineSearchRadius = 32;
    public static volatile int treeSearchRadius = 32;
    public static volatile boolean fellCollectDrops = true;
    public static volatile boolean mineCollectDrops = true;
    public static volatile boolean treePerception = true;
    public static volatile int treePerceptionRadius = 12;
    public static volatile PathingConfig pathing = PathingConfig.DEFAULTS;
    /** Block ids and tags pathing never breaks; parsed once per applied config. */
    public static volatile BlockList breakList =
            BlockList.parse(PathingConfig.DEFAULTS.breakBlacklist(), entry -> {});
    /** Block ids and tags pathing never places: the break list plus the place additions. */
    public static volatile BlockList placeList =
            BlockList.parse(PathingConfig.DEFAULTS.placeBlacklist(), entry -> {});

    private TaskSettings() {}
}
