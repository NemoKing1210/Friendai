package dev.friedai.core;

import dev.friedai.FriedaiMod;
import dev.friedai.config.ConfigStore;
import dev.friedai.config.ServerConfig;
import dev.friedai.entity.CompanionManager;
import dev.friedai.task.TaskSettings;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;

/** Per-server mod state: the loaded config and the brains. */
public final class FriedaiServerState {
    private static final Map<MinecraftServer, FriedaiServerState> STATES = new WeakHashMap<>();

    private final ConfigStore<ServerConfig> store;
    private final BrainService brains;
    private ServerConfig config;

    private FriedaiServerState(MinecraftServer server) {
        this.store = new ConfigStore<>(
                FabricLoader.getInstance().getConfigDir().resolve("friedai-server.json"),
                ServerConfig.class,
                ServerConfig::defaults,
                ServerConfig.VERSION,
                ServerConfig::migrate);
        this.config = store.load();
        logProblems(config.validate());
        applyTaskSettings(config);
        this.brains = new BrainService(server, config);
        CompanionManager.get(server).addListener(brains::onCompanionRemoved);
    }

    public static synchronized FriedaiServerState get(MinecraftServer server) {
        return STATES.computeIfAbsent(server, FriedaiServerState::new);
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
        problems.forEach(p -> FriedaiMod.LOGGER.warn("Config: {}", p));
    }
}
