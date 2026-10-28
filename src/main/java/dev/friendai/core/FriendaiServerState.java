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
import dev.friendai.server.configuration.ServerConfiguration;
import dev.friendai.server.configuration.ServerSettings;
import dev.friendai.skin.SkinStore;
import dev.friendai.task.builtin.RefreshTradesTask;
import dev.friendai.task.trading.BarterDropTracker;
import dev.friendai.task.trading.MerchantAdapter;
import dev.friendai.task.trading.MerchantLeases;
import dev.friendai.task.trading.PiglinBarterAdapter;
import dev.friendai.task.trading.TradeSessionGuard;
import dev.friendai.task.trading.TraderObserver;
import dev.friendai.trading.TradeStore;
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
    private final ServerConfiguration configuration;
    private final BrainService brains;
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
    private final Map<UUID, Long> nextTradingRefresh = new java.util.HashMap<>();

    private FriendaiServerState(MinecraftServer server) {
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
        this.skills = new SkillService(
                server.getWorldPath(LevelResource.ROOT).resolve("friendai").resolve("skills"),
                () -> this.config,
                memory);
        this.goals = new GoalService(memory::book, () -> this.config.agent.maxReplans);
        this.freeTime = new dev.friendai.agent.autonomy.FreeTimeService(memory::book);
        this.skins = new SkinStore(
                server.getWorldPath(LevelResource.ROOT).resolve("friendai").resolve("skins"));
        this.knowledge = new KnowledgeStore(
                server.getWorldPath(LevelResource.ROOT).resolve("friendai").resolve("knowledge"),
                KnowledgeLimits.DEFAULTS);
        this.places = new PlaceStore(
                server.getWorldPath(LevelResource.ROOT).resolve("friendai").resolve("places"), PlaceLimits.DEFAULTS);
        this.placed = new PlacedStore(
                server.getWorldPath(LevelResource.ROOT).resolve("friendai").resolve("placed"), PlacedLimits.DEFAULTS);
        this.trading = new TradeStore(
                server.getWorldPath(LevelResource.ROOT).resolve("friendai").resolve("trading"));
        this.merchantLeases = new MerchantLeases();
        this.tradeSessionGuard = new TradeSessionGuard(merchantLeases);
        this.traderObserver = new TraderObserver(
                trading, () -> this.config.tasks.trading.observationRadius, () -> this.config.tasks.trading.enabled);
        this.merchantAdapter = new MerchantAdapter(trading, merchantLeases, tradeSessionGuard);
        this.resources = new ResourceCandidates(knowledge);
        this.observations = new ObservationScheduler(
                resources::observe,
                resources::observeCaveEntrance,
                () -> this.config.tasks.resources.observationRadius);
        this.resources.attach(observations);
        this.brains = new BrainService(server, config, memory);
        this.skills.setTeachingContextSource(brains::teachingContext);
        this.chunkLoader = new CompanionChunkLoader(server, () -> this.config);
        this.trust = new TrustConfirmations(server, memory, brains, () -> this.config);
        CompanionManager.get(server).addListener(brains::onCompanionRemoved);
    }

    public static synchronized FriendaiServerState get(MinecraftServer server) {
        return STATES.computeIfAbsent(server, FriendaiServerState::new);
    }

    public static synchronized void close(MinecraftServer server) {
        FriendaiServerState state = STATES.remove(server);
        if (state != null) state.shutdown();
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

    public BrainService brains() {
        return brains;
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

    private void shutdown() {
        tradeSessionGuard.closeAll("server stopped");
        trading.close();
        knowledge.close();
        places.close();
        placed.close();
        skills.close();
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

    /** Schedules one local offer inspection only while the owner's task runner has no work to displace. */
    public void tickTrading() {
        if (!config.tasks.trading.enabled || !config.tasks.trading.idleRefresh) return;
        long now = server.overworld().getGameTime();
        for (CompanionEntity companion : CompanionManager.get(server).all()) {
            UUID owner = companion.ownerUuid();
            if (owner == null || companion.owner().isEmpty()) continue;
            if (nextTradingRefresh.getOrDefault(owner, 0L) > now) continue;
            var runner = companion.taskRunner();
            if (runner.hasWork() || runner.paused()) continue;
            List<dev.friendai.trading.TraderRecord> nearby = traderObserver.observe(companion);
            var ownerBook = trading.book(owner);
            dev.friendai.trading.TraderRecord candidate = nearby.stream()
                    .filter(record -> !"minecraft:piglin".equals(record.entityType()))
                    .map(record -> ownerBook.trader(record.id()).orElse(record))
                    .sorted(java.util.Comparator.comparing((dev.friendai.trading.TraderRecord record) ->
                                    record.status() == dev.friendai.trading.TraderRecord.Status.VERIFIED)
                            .thenComparingLong(record -> record.offersSeen() < 0 ? -1 : record.offersSeen())
                            .thenComparing(dev.friendai.trading.TraderRecord::id))
                    .findFirst()
                    .orElse(null);
            nextTradingRefresh.put(owner, now + config.tasks.trading.idleRefreshIntervalTicks);
            if (candidate != null) {
                runner.start(
                        new RefreshTradesTask(List.of(candidate.id()), now + config.tasks.trading.maxWaitTicks),
                        "refresh_trades");
                break;
            }
        }
        nextTradingRefresh
                .keySet()
                .removeIf(owner -> CompanionManager.get(server).all().stream()
                        .noneMatch(companion -> owner.equals(companion.ownerUuid())));
    }

    /** Applies a config live: the next brain request goes through the new client. Returns the fixes made. */
    public List<String> applyConfig(ServerConfig cfg) {
        boolean tradingWasEnabled = configuration.current().trading().enabled();
        List<String> problems = configuration.apply(cfg);
        logProblems(problems);
        this.config = cfg;
        if (tradingWasEnabled && !configuration.current().trading().enabled()) {
            tradeSessionGuard.closeAll("trading disabled by server config");
            brains.invalidateTradingAuthorities("trading disabled by server config");
        }
        syncCompanionNames(cfg);
        ServerSettings applied = configuration.current();
        for (CompanionEntity companion : CompanionManager.get(server).all()) {
            companion.taskRunner().applySettings(applied);
        }
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

    private static void logProblems(List<String> problems) {
        problems.forEach(p -> FriendaiMod.LOGGER.warn("Config: {}", p));
    }
}
