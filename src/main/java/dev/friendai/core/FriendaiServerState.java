package dev.friendai.core;

import dev.friendai.FriendaiMod;
import dev.friendai.config.ConfigStore;
import dev.friendai.config.ServerConfig;
import dev.friendai.entity.CompanionChunkLoader;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.memory.MemoryStore;
import dev.friendai.memory.MemoryViewers;
import dev.friendai.pathing.BlockList;
import dev.friendai.task.TaskSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/** Per-server mod state: the loaded config and the brains. */
public final class FriendaiServerState {
    private static final Map<MinecraftServer, FriendaiServerState> STATES = new WeakHashMap<>();

    private final MinecraftServer server;
    private final ConfigStore<ServerConfig> store;
    private final BrainService brains;
    private final CompanionChunkLoader chunkLoader;
    private final MemoryStore memory;
    private final MemoryViewers memoryViewers = new MemoryViewers();
    private final TrustConfirmations trust;
    private ServerConfig config;
    private long lastTrustTick;

    private FriendaiServerState(MinecraftServer server) {
        this.server = server;
        this.store = new ConfigStore<>(
                FabricLoader.getInstance().getConfigDir().resolve("friendai-server.json"),
                ServerConfig.class,
                ServerConfig::defaults,
                ServerConfig.VERSION,
                ServerConfig::migrate);
        this.config = store.load();
        logProblems(config.validate());
        applyTaskSettings(config);
        this.memory = new MemoryStore(
                server.getWorldPath(LevelResource.ROOT).resolve("friendai").resolve("memory"),
                System::currentTimeMillis);
        this.brains = new BrainService(server, config, memory);
        this.chunkLoader = new CompanionChunkLoader(server, () -> this.config);
        this.trust = new TrustConfirmations(server, memory, brains, () -> this.config);
        CompanionManager.get(server).addListener(brains::onCompanionRemoved);
    }

    public static synchronized FriendaiServerState get(MinecraftServer server) {
        return STATES.computeIfAbsent(server, FriendaiServerState::new);
    }

    public static synchronized void close(MinecraftServer server) {
        STATES.remove(server);
    }

    public ServerConfig config() {
        return config;
    }

    public ConfigStore<ServerConfig> store() {
        return store;
    }

    public BrainService brains() {
        return brains;
    }

    /** Keeps loaded companions' chunks ticking and restores them on startup. */
    public CompanionChunkLoader chunkLoader() {
        return chunkLoader;
    }

    /** Per-owner memory books: trust, player records, food rules and the companion's name. */
    public MemoryStore memory() {
        return memory;
    }

    /** The name the owner gave their companion, or the configured default. */
    public String companionName(UUID owner) {
        return memory.book(owner).companionName().orElse(config.companion.name);
    }

    /** Who has the memory tab open, and the last book revision each received. */
    public MemoryViewers memoryViewers() {
        return memoryViewers;
    }

    /** Trust changes waiting for the owner's confirmation. */
    public TrustConfirmations trust() {
        return trust;
    }

    /** Expires pending trust requests, checked once per second. */
    public void tick(long now) {
        if (now - lastTrustTick < 1_000) {
            return;
        }
        lastTrustTick = now;
        trust.tick(now);
    }

    /** Applies a config live: the next brain request goes through the new client. Returns the fixes made. */
    public List<String> applyConfig(ServerConfig cfg) {
        List<String> problems = cfg.validate();
        logProblems(problems);
        this.config = cfg;
        syncCompanionNames(cfg);
        applyTaskSettings(cfg);
        brains.reload(cfg);
        return problems;
    }

    /** A config name reaches companions that still use the default; a name the owner chose wins. */
    private void syncCompanionNames(ServerConfig cfg) {
        for (CompanionEntity companion : CompanionManager.get(server).all()) {
            UUID owner = companion.ownerUuid();
            if (owner != null && memory.book(owner).companionName().isEmpty()) {
                companion.applyName(cfg.companion.name);
            }
        }
    }

    private static void applyTaskSettings(ServerConfig cfg) {
        TaskSettings.taskTimeoutSec = cfg.tasks.taskTimeoutSec;
        TaskSettings.queueMaxJobs = cfg.tasks.queueMaxJobs;
        TaskSettings.queueMaxPlans = cfg.tasks.queueMaxPlans;
        TaskSettings.queueBlockedNoticeSec = cfg.tasks.queueBlockedNoticeSec;
        TaskSettings.queueMaxStepsPerAdd = cfg.tasks.queueMaxStepsPerAdd;
        TaskSettings.defaultFollow = cfg.tasks.defaultFollow;
        TaskSettings.mineSearchRadius = cfg.tasks.mineSearchRadius;
        TaskSettings.treeSearchRadius = cfg.tasks.treeSearchRadius;
        TaskSettings.fellCollectDrops = cfg.tasks.fellCollectDrops;
        TaskSettings.mineCollectDrops = cfg.tasks.mineCollectDrops;
        TaskSettings.treePerception = cfg.tasks.treePerception;
        TaskSettings.treePerceptionRadius = cfg.tasks.treePerceptionRadius;
        TaskSettings.maxNearbySpawned = cfg.structures.maxNearbySpawned;
        TaskSettings.pathing = cfg.pathing.toPathingConfig();
        TaskSettings.breakList = BlockList.parse(
                cfg.pathing.breakBlacklist,
                entry -> FriendaiMod.LOGGER.warn("Unknown pathing break blacklist entry: {}", entry));
        List<String> placeEntries = new ArrayList<>(cfg.pathing.breakBlacklist);
        if (cfg.pathing.placeBlacklist != null) {
            placeEntries.addAll(cfg.pathing.placeBlacklist);
        }
        TaskSettings.placeList = BlockList.parse(placeEntries, entry -> {});
    }

    private static void logProblems(List<String> problems) {
        problems.forEach(p -> FriendaiMod.LOGGER.warn("Config: {}", p));
    }
}
