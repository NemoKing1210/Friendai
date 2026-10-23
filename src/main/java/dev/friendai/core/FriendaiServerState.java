package dev.friendai.core;

import dev.friendai.FriendaiMod;
import dev.friendai.config.ConfigStore;
import dev.friendai.config.ServerConfig;
import dev.friendai.entity.CompanionManager;
import dev.friendai.memory.MemoryStore;
import dev.friendai.memory.MemoryViewers;
import dev.friendai.task.TaskSettings;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/** Per-server mod state: the loaded config and the brains. */
public final class FriendaiServerState {
    private static final Map<MinecraftServer, FriendaiServerState> STATES = new WeakHashMap<>();

    private final ConfigStore<ServerConfig> store;
    private final BrainService brains;
    private final MemoryStore memory;
    private final MemoryViewers memoryViewers = new MemoryViewers();
    private ServerConfig config;

    private FriendaiServerState(MinecraftServer server) {
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

    /** Per-owner memory books: trust and player records. */
    public MemoryStore memory() {
        return memory;
    }

    /** Who has the memory tab open, and the last book revision each received. */
    public MemoryViewers memoryViewers() {
        return memoryViewers;
    }

    /** Applies a config live: the next brain request goes through the new client. Returns the fixes made. */
    public List<String> applyConfig(ServerConfig cfg) {
        List<String> problems = cfg.validate();
        logProblems(problems);
        this.config = cfg;
        applyTaskSettings(cfg);
        brains.reload(cfg);
        return problems;
    }

    private static void applyTaskSettings(ServerConfig cfg) {
        TaskSettings.taskTimeoutSec = cfg.tasks.taskTimeoutSec;
        TaskSettings.defaultFollow = cfg.tasks.defaultFollow;
        TaskSettings.mineSearchRadius = cfg.tasks.mineSearchRadius;
    }

    private static void logProblems(List<String> problems) {
        problems.forEach(p -> FriendaiMod.LOGGER.warn("Config: {}", p));
    }
}
