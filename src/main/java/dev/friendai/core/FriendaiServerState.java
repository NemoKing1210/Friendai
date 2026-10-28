package dev.friendai.core;

import dev.friendai.FriendaiMod;
import dev.friendai.agent.goal.GoalService;
import dev.friendai.config.ConfigStore;
import dev.friendai.config.ServerConfig;
import dev.friendai.entity.CompanionChunkLoader;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionLifeData;
import dev.friendai.entity.CompanionManager;
import dev.friendai.knowledge.KnowledgeLimits;
import dev.friendai.knowledge.KnowledgeStore;
import dev.friendai.memory.MemoryStore;
import dev.friendai.memory.MemoryViewers;
import dev.friendai.perception.resource.ObservationScheduler;
import dev.friendai.perception.resource.ResourceCandidates;
import dev.friendai.place.PlaceLimits;
import dev.friendai.place.PlaceStore;
import dev.friendai.placed.PlacedLimits;
import dev.friendai.placed.PlacedStore;
import dev.friendai.server.agent.CompanionSessions;
import dev.friendai.server.configuration.ServerConfiguration;
import dev.friendai.server.configuration.ServerSettings;
import dev.friendai.server.runtime.RuntimeResources;
import dev.friendai.server.runtime.ServerRuntimeRegistry;
import dev.friendai.skin.SkinStore;
import dev.friendai.task.trading.BarterDropTracker;
import dev.friendai.task.trading.MerchantAdapter;
import dev.friendai.task.trading.MerchantLeases;
import dev.friendai.task.trading.PiglinBarterAdapter;
import dev.friendai.task.trading.TradeSessionGuard;
import dev.friendai.task.trading.TraderObserver;
import dev.friendai.trading.TradeStore;
import java.util.List;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/** Existing server service graph, now constructed and closed only by {@code ServerRuntime}. */
public final class FriendaiServerState {
    private final MinecraftServer server;
    private final ConfigStore<ServerConfig> store;
    private final ServerConfiguration configuration;
    private final CompanionSessions sessions;
    private final CompanionChunkLoader chunkLoader;
    private final MemoryStore memory;
    private final SkillService skills;
    private final GoalService goals;
    private final dev.friendai.agent.autonomy.FreeTimeService freeTime;
    private final SkinStore skins;
    private final KnowledgeStore knowledge;
    private final PlaceStore places;
    private final PlacedStore placed;
    private final TradeStore trading;
    private final MerchantLeases merchantLeases;
    private final TradeSessionGuard tradeSessionGuard;
    private final TraderObserver traderObserver;
    private final BarterDropTracker barterDropTracker = new BarterDropTracker();
    private final PiglinBarterAdapter piglinBarterAdapter = new PiglinBarterAdapter(barterDropTracker);
    private final MerchantAdapter merchantAdapter;
    private final ObservationScheduler observations;
    private final ResourceCandidates resources;
    private final MemoryViewers memoryViewers = new MemoryViewers();
    private final MemoryViewers placeViewers = new MemoryViewers();
    private final dev.friendai.knowledge.search.SearchHintStore searchHints =
            new dev.friendai.knowledge.search.SearchHintStore();
    private final TrustConfirmations trust;
    private ServerConfig config;
    private long lastTrustTick;
    private boolean stopping;

    public FriendaiServerState(MinecraftServer server, RuntimeResources resources) {
        this.server = server;
        this.store = new ConfigStore<>(
                FabricLoader.getInstance().getConfigDir().resolve("friendai-server.json"),
                ServerConfig.class,
                ServerConfig::defaults,
                ServerConfig.VERSION,
                ServerConfig::migrate);
        this.config = store.load();
        this.configuration = new ServerConfiguration(
                entry -> FriendaiMod.LOGGER.warn("Unknown pathing break blacklist entry: {}", entry));
        logProblems(configuration.apply(config));
        this.memory = new MemoryStore(
                server.getWorldPath(LevelResource.ROOT).resolve("friendai").resolve("memory"),
                System::currentTimeMillis);
        resources.onClose(memory::flushAll);
        this.skills = resources.own(new SkillService(
                server.getWorldPath(LevelResource.ROOT).resolve("friendai").resolve("skills"),
                () -> this.config,
                memory));
        this.goals = new GoalService(memory::book, () -> this.config.agent.maxReplans);
        this.freeTime = new dev.friendai.agent.autonomy.FreeTimeService(memory::book);
        this.skins = new SkinStore(
                server.getWorldPath(LevelResource.ROOT).resolve("friendai").resolve("skins"));
        this.knowledge = resources.own(new KnowledgeStore(
                server.getWorldPath(LevelResource.ROOT).resolve("friendai").resolve("knowledge"),
                KnowledgeLimits.DEFAULTS));
        this.places = resources.own(new PlaceStore(
                server.getWorldPath(LevelResource.ROOT).resolve("friendai").resolve("places"), PlaceLimits.DEFAULTS));
        this.placed = resources.own(new PlacedStore(
                server.getWorldPath(LevelResource.ROOT).resolve("friendai").resolve("placed"), PlacedLimits.DEFAULTS));
        this.trading = resources.own(new TradeStore(
                server.getWorldPath(LevelResource.ROOT).resolve("friendai").resolve("trading")));
        this.merchantLeases = new MerchantLeases();
        this.tradeSessionGuard = new TradeSessionGuard(merchantLeases);
        resources.onClose(() -> tradeSessionGuard.closeAll("server stopped"));
        this.traderObserver = new TraderObserver(
                trading, () -> this.config.tasks.trading.observationRadius, () -> this.config.tasks.trading.enabled);
        this.merchantAdapter = new MerchantAdapter(trading, merchantLeases, tradeSessionGuard);
        this.resources = new ResourceCandidates(knowledge);
        this.observations = new ObservationScheduler(
                this.resources::observe,
                this.resources::observeCaveEntrance,
                () -> this.config.tasks.resources.observationRadius);
        this.resources.attach(observations);
        this.sessions = resources.own(new CompanionSessions(
                server, config, memory, this::settings, skills, goals, trading, places, this.resources, freeTime));
        this.skills.setTeachingContextSource(sessions::teachingContext);
        this.chunkLoader = new CompanionChunkLoader(server, () -> this.config);
        resources.onClose(chunkLoader::stop);
        this.trust = new TrustConfirmations(server, memory, sessions, () -> this.config);
        CompanionManager manager = CompanionManager.get(server);
        CompanionManager.Listener listener = sessions::onCompanionRemoved;
        manager.addListener(listener);
        resources.onClose(() -> manager.removeListener(listener));
    }

    /** Temporary bridge while service callers move to their owning adapters; expiry owners are in the edge manifest. */
    public static FriendaiServerState get(MinecraftServer server) {
        return ServerRuntimeRegistry.getOrCreate(server).state();
    }

    /** Temporary test/transition bridge; lifecycle callbacks use ServerRuntimeRegistry directly. */
    public static void close(MinecraftServer server) {
        ServerRuntimeRegistry.removeAndClose(server);
    }

    public ServerConfig config() {
        return config;
    }

    public ServerSettings settings() {
        return configuration.current();
    }

    public ConfigStore<ServerConfig> store() {
        return store;
    }

    public CompanionSessions sessions() {
        return sessions;
    }

    /** Keeps loaded companions' chunks ticking and restores them on startup. */
    public CompanionChunkLoader chunkLoader() {
        return chunkLoader;
    }

    /** Per-owner auto-respawn preferences and bed checkpoints. */
    public CompanionLifeData companionLife() {
        return CompanionLifeData.get(server);
    }

    /** The owners' goal boards, kept in their memory books. */
    /** Each owner's free-time state, kept in their memory book. */
    public dev.friendai.agent.autonomy.FreeTimeService freeTime() {
        return freeTime;
    }

    public GoalService goals() {
        return goals;
    }

    /** Per-owner memory books: trust, player records, food rules and the companion's name. */
    public SkillService skills() {
        return skills;
    }

    public MemoryStore memory() {
        return memory;
    }

    /** Custom companion skins, one per owner. */
    public SkinStore skins() {
        return skins;
    }

    public KnowledgeStore knowledge() {
        return knowledge;
    }

    /** Per-owner places the companion knows. */
    public PlaceStore places() {
        return places;
    }

    /** Per-owner record of the blocks the companion placed and may still be there. */
    public PlacedStore placed() {
        return placed;
    }
    /** Per-owner trader observations and informational order history. */
    public TradeStore trading() {
        return trading;
    }

    public MerchantLeases merchantLeases() {
        return merchantLeases;
    }

    public TradeSessionGuard tradeSessionGuard() {
        return tradeSessionGuard;
    }

    public TraderObserver traderObserver() {
        return traderObserver;
    }

    public BarterDropTracker barterDropTracker() {
        return barterDropTracker;
    }

    public PiglinBarterAdapter piglinBarterAdapter() {
        return piglinBarterAdapter;
    }

    public MerchantAdapter merchantAdapter() {
        return merchantAdapter;
    }

    public ObservationScheduler observations() {
        return observations;
    }

    public ResourceCandidates resources() {
        return resources;
    }

    public void stopAcceptingWork() {
        if (stopping) return;
        stopping = true;
        sessions.stopAcceptingWork();
        tradeSessionGuard.closeAll("server stopping");
        chunkLoader.stop();
        for (CompanionEntity companion : CompanionManager.get(server).all())
            companion.taskRunner().suspend();
    }

    /** The name the owner gave their companion, or the configured default. */
    public String companionName(UUID owner) {
        return memory.book(owner).companionName().orElse(config.companion.name);
    }

    /** Who has the memory tab open, and the last book revision each received. */
    public MemoryViewers memoryViewers() {
        return memoryViewers;
    }

    /** Who has the places tab open, and the last registry revision each received. */
    public MemoryViewers placeViewers() {
        return placeViewers;
    }

    /** Per-owner search hints: short-lived model steering, never a waypoint. */
    public dev.friendai.knowledge.search.SearchHintStore searchHints() {
        return searchHints;
    }

    /** Trust changes waiting for the owner's confirmation. */
    public TrustConfirmations trust() {
        return trust;
    }

    /** Expires pending trust requests, checked once per second. */
    public void tick(long now) {
        skills.tick();
        if (now - lastTrustTick < 1_000) {
            return;
        }
        lastTrustTick = now;
        trust.tick(now);
    }

    /** Applies a config live: the next brain request goes through the new client. Returns the fixes made. */
    public List<String> applyConfig(ServerConfig cfg) {
        boolean tradingWasEnabled = configuration.current().trading().enabled();
        List<String> problems = configuration.apply(cfg);
        logProblems(problems);
        this.config = cfg;
        if (tradingWasEnabled && !configuration.current().trading().enabled()) {
            tradeSessionGuard.closeAll("trading disabled by server config");
            sessions.invalidateTradingAuthorities("trading disabled by server config");
        }
        syncCompanionNames(cfg);
        ServerSettings applied = configuration.current();
        for (CompanionEntity companion : CompanionManager.get(server).all()) {
            companion.taskRunner().applySettings(applied);
        }
        sessions.reload(cfg);
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

    private static void logProblems(List<String> problems) {
        problems.forEach(p -> FriendaiMod.LOGGER.warn("Config: {}", p));
    }
}
