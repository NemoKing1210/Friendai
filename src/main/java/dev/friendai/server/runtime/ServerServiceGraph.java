package dev.friendai.server.runtime;

import dev.friendai.FriendaiMod;
import dev.friendai.agent.goal.GoalService;
import dev.friendai.entity.CompanionChunkLoader;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionLifeData;
import dev.friendai.entity.CompanionManager;
import dev.friendai.features.cartography.minecraft.TerrainSamplerService;
import dev.friendai.features.knowledge.KnowledgeLimits;
import dev.friendai.features.memory.CompanionProfileRepository;
import dev.friendai.features.memory.MemoryViewers;
import dev.friendai.features.memory.PlayerRecord;
import dev.friendai.features.memory.PlayerRef;
import dev.friendai.features.memory.TrustRequest;
import dev.friendai.features.memory.minecraft.tool.TrustAccess;
import dev.friendai.features.placements.PlacedLimits;
import dev.friendai.features.placements.minecraft.PlacedService;
import dev.friendai.features.places.PlaceLimits;
import dev.friendai.features.places.PlaceSettings;
import dev.friendai.features.places.minecraft.PlaceService;
import dev.friendai.features.trading.TradeAuthority;
import dev.friendai.features.trading.TradeBook;
import dev.friendai.features.trading.TradingRuntimeSettings;
import dev.friendai.features.trading.minecraft.tool.TradingToolAccess;
import dev.friendai.features.trading.minecraft.trading.BarterDropTracker;
import dev.friendai.features.trading.minecraft.trading.MerchantAdapter;
import dev.friendai.features.trading.minecraft.trading.MerchantLeases;
import dev.friendai.features.trading.minecraft.trading.PiglinBarterAdapter;
import dev.friendai.features.trading.minecraft.trading.TradeSessionGuard;
import dev.friendai.features.trading.minecraft.trading.TraderObserver;
import dev.friendai.infrastructure.config.ConfigStore;
import dev.friendai.infrastructure.config.ConfigStores;
import dev.friendai.infrastructure.config.ServerConfig;
import dev.friendai.infrastructure.persistence.OwnerStoreWriter;
import dev.friendai.infrastructure.persistence.appearance.SkinStore;
import dev.friendai.infrastructure.persistence.autonomy.FreeTimeStore;
import dev.friendai.infrastructure.persistence.cartography.TerrainStore;
import dev.friendai.infrastructure.persistence.goals.GoalStore;
import dev.friendai.infrastructure.persistence.knowledge.KnowledgeStore;
import dev.friendai.infrastructure.persistence.memory.CompanionProfileStore;
import dev.friendai.infrastructure.persistence.memory.MemoryStore;
import dev.friendai.infrastructure.persistence.placements.PlacedStore;
import dev.friendai.infrastructure.persistence.places.PlaceStore;
import dev.friendai.infrastructure.persistence.social.SocialMemoryStore;
import dev.friendai.infrastructure.persistence.trading.TradeStore;
import dev.friendai.infrastructure.persistence.transcript.TranscriptStore;
import dev.friendai.perception.resource.ObservationScheduler;
import dev.friendai.perception.resource.ResourceCandidates;
import dev.friendai.server.agent.CompanionSessions;
import dev.friendai.server.configuration.ServerConfiguration;
import dev.friendai.server.configuration.ServerSettings;
import dev.friendai.server.execution.persistence.TaskPersistenceCoordinator;
import dev.friendai.server.instructions.SkillService;
import dev.friendai.server.memory.TrustConfirmations;
import java.util.List;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/** Per-server domain services, composed and owned by {@link ServerRuntime}. */
final class ServerServiceGraph implements TradingToolAccess, TrustAccess {
    private final MinecraftServer server;
    private final ConfigStore<ServerConfig> store;
    private final ServerConfiguration configuration;
    private final CompanionSessions sessions;
    private final TaskPersistenceCoordinator taskPersistence;
    private final CompanionChunkLoader chunkLoader;
    private final MemoryStore memory;
    private final TranscriptStore transcripts;
    private final CompanionProfileStore profiles;
    private final GoalStore goalStore;
    private final FreeTimeStore freeTimeStore;
    private final SocialMemoryStore socialMemoryStore;
    private final TerrainStore terrainStore;
    private final TerrainSamplerService terrainSampler;
    private final dev.friendai.features.inventory.minecraft.storage.StorageIndexService storageService;
    private final SkillService skills;
    private final GoalService goals;
    private final dev.friendai.agent.autonomy.FreeTimeService freeTime;
    private final dev.friendai.infrastructure.persistence.routines.RoutineStore routines;
    private final SkinStore skins;
    private final KnowledgeStore knowledge;
    private final PlaceStore places;
    private final PlacedStore placed;
    private final PlaceService placeService;
    private final PlacedService placedService;
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
    private final dev.friendai.features.knowledge.search.SearchHintStore searchHints =
            new dev.friendai.features.knowledge.search.SearchHintStore();
    private final TrustConfirmations trust;
    private final dev.friendai.debug.DiagnosticService diagnostics;
    private final dev.friendai.debug.LogWriterService fileLog;
    private final java.nio.file.Path logGameRoot;
    private final java.nio.file.Path logWorldPath;
    private final String logBoot;
    private final String logWorld;
    private ServerConfig config;
    private long lastTrustTick;
    private boolean stopping;

    ServerServiceGraph(MinecraftServer server, RuntimeResources resources) {
        this.server = server;
        OwnerStoreWriter writer = resources.own(new OwnerStoreWriter());
        java.nio.file.Path worldPath;
        try {
            worldPath = server.getWorldPath(LevelResource.ROOT);
        } catch (RuntimeException unavailable) {
            worldPath = null;
        }
        java.nio.file.Path logRoot = worldPath == null
                ? dev.friendai.debug.LogPaths.unavailableRoot(
                        FabricLoader.getInstance().getGameDir())
                : dev.friendai.debug.LogPaths.worldRoot(worldPath);
        this.diagnostics = resources.own(new dev.friendai.debug.DiagnosticService(logRoot));
        this.store =
                ConfigStores.server(FabricLoader.getInstance().getConfigDir().resolve("friendai-server.json"));
        this.config = store.loadWithDefaultsOnUnsupportedVersion();
        this.configuration = new ServerConfiguration(
                entry -> FriendaiMod.LOGGER.warn("Unknown pathing break blacklist entry: {}", entry));
        logProblems(configuration.apply(config));
        String boot = dev.friendai.foundation.diagnostics.LogNames.bootId();
        this.logGameRoot =
                dev.friendai.debug.LogPaths.gameRoot(FabricLoader.getInstance().getGameDir());
        this.logWorldPath = worldPath;
        this.logBoot = boot;
        this.logWorld = dev.friendai.debug.LogPaths.worldName(worldPath);
        this.fileLog = resources.own(new dev.friendai.debug.LogWriterService(
                logRoot,
                dev.friendai.debug.LogWriterScope.SERVER,
                boot,
                logWorld,
                modVersion("friendai"),
                modVersion("minecraft"),
                owner -> "",
                config.logging.toSettings(),
                text -> text,
                System::currentTimeMillis));
        dev.friendai.debug.LogSinkHub.add(fileLog);
        dev.friendai.debug.LogWorldIndex.append(
                logGameRoot,
                "world.load",
                logWorld,
                logWorldPath,
                logBoot,
                modVersion("friendai"),
                modVersion("minecraft"),
                System.currentTimeMillis());
        this.memory = resources.own(new MemoryStore(
                server.getWorldPath(LevelResource.ROOT).resolve("friendai").resolve("memory"), writer));
        this.transcripts = resources.own(new TranscriptStore(
                server.getWorldPath(LevelResource.ROOT).resolve("friendai").resolve("transcript"),
                () -> this.config.memory.transcriptLimits(),
                writer));
        this.profiles = resources.own(new CompanionProfileStore(
                server.getWorldPath(LevelResource.ROOT).resolve("friendai").resolve("profiles"), writer));
        this.skills = resources.own(new SkillService(
                server.getWorldPath(LevelResource.ROOT).resolve("friendai").resolve("skills"),
                () -> this.config,
                profiles,
                writer));
        this.goalStore = resources.own(new GoalStore(
                server.getWorldPath(LevelResource.ROOT).resolve("friendai").resolve("goals"), writer));
        this.freeTimeStore = resources.own(new FreeTimeStore(
                server.getWorldPath(LevelResource.ROOT).resolve("friendai").resolve("free-time"), writer));
        this.routines = resources.own(new dev.friendai.infrastructure.persistence.routines.RoutineStore(
                server.getWorldPath(LevelResource.ROOT).resolve("friendai").resolve("routines"), writer));
        this.socialMemoryStore = resources.own(new SocialMemoryStore(
                server.getWorldPath(LevelResource.ROOT).resolve("friendai").resolve("social"), writer));
        this.terrainStore = resources.own(new TerrainStore(
                server.getWorldPath(LevelResource.ROOT).resolve("friendai").resolve("terrain"), writer));
        this.terrainSampler = new TerrainSamplerService(terrainStore);
        this.goals = new GoalService(goalStore, () -> this.config.agent.maxReplans);
        this.freeTime = new dev.friendai.agent.autonomy.FreeTimeService(freeTimeStore);
        this.skins = new SkinStore(
                server.getWorldPath(LevelResource.ROOT).resolve("friendai").resolve("skins"));
        this.knowledge = resources.own(new KnowledgeStore(
                server.getWorldPath(LevelResource.ROOT).resolve("friendai").resolve("knowledge"),
                KnowledgeLimits.DEFAULTS,
                writer));
        this.storageService = new dev.friendai.features.inventory.minecraft.storage.StorageIndexService(
                owner -> knowledge.book(owner), () -> dev.friendai.features.storage.StorageLimits.DEFAULTS);
        this.places = resources.own(new PlaceStore(
                server.getWorldPath(LevelResource.ROOT).resolve("friendai").resolve("places"),
                PlaceLimits.DEFAULTS,
                writer));
        this.placed = resources.own(new PlacedStore(
                server.getWorldPath(LevelResource.ROOT).resolve("friendai").resolve("placed"),
                PlacedLimits.DEFAULTS,
                writer));
        this.placeService = new PlaceService(places::book, this::placeSettings).onRemembered((companion, place) -> {
            UUID owner = companion.ownerUuid();
            if (owner == null) return;
            var grid = terrainSampler.grid(owner);
            grid.addSite(new dev.friendai.features.cartography.TerrainSite(
                    place.dimension(),
                    place.anchor().x(),
                    place.anchor().z(),
                    place.kind().name().toLowerCase(java.util.Locale.ROOT),
                    place.name(),
                    place.updatedTick()));
            terrainStore.save(owner, grid);
        });
        this.placedService = new PlacedService(placed::book);
        this.trading = resources.own(new TradeStore(
                server.getWorldPath(LevelResource.ROOT).resolve("friendai").resolve("trading"), writer));
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
                server,
                config,
                memory,
                transcripts,
                profiles,
                this::settings,
                skills,
                goals,
                trading,
                places,
                placeService,
                placedService,
                this.resources,
                freeTime,
                routines,
                this,
                this,
                searchHints,
                storageService,
                fileLog));
        fileLog.updateRedactor(sessions.redactor());
        this.taskPersistence = new TaskPersistenceCoordinator(
                this::settings,
                sessions.taskRestoreRegistry(),
                sessions::prepareForTaskRestore,
                owner -> sessions.tradeAuthorityFor(owner).invalidateAll("trading authority expired after restart"),
                owner -> knowledge.book(owner).searchLedger(),
                freeTime);
        this.skills.setTeachingContextSource(sessions::teachingContext);
        this.chunkLoader = new CompanionChunkLoader(server, () -> this.config);
        resources.onClose(chunkLoader::stop);
        this.trust = new TrustConfirmations(server, memory, sessions, () -> this.config);
        CompanionManager manager = CompanionManager.get(server);
        CompanionManager.Listener listener = sessions::onCompanionRemoved;
        manager.addListener(listener);
        resources.onClose(() -> manager.removeListener(listener));
    }

    public ServerConfig config() {
        return config;
    }

    List<String> applyLoggingLevel(String level) {
        ServerConfig cfg = config;
        cfg.logging.level = level;
        List<String> problems = cfg.validate();
        store.save(cfg);
        applyConfig(cfg);
        return problems;
    }

    int pruneLogs() {
        return fileLog.pruneNow();
    }

    public ServerSettings settings() {
        return configuration.current();
    }

    public dev.friendai.debug.DiagnosticService diagnostics() {
        return diagnostics;
    }

    public dev.friendai.debug.LogWriterService fileLog() {
        return fileLog;
    }

    public ConfigStore<ServerConfig> store() {
        return store;
    }

    public CompanionSessions sessions() {
        return sessions;
    }

    public TaskPersistenceCoordinator taskPersistence() {
        return taskPersistence;
    }

    /** Keeps loaded companions' chunks ticking and restores them on startup. */
    public CompanionChunkLoader chunkLoader() {
        return chunkLoader;
    }

    /** Per-owner auto-respawn preferences and bed checkpoints. */
    public CompanionLifeData companionLife() {
        return CompanionLifeData.get(server);
    }

    /** Each owner's free-time state, backed by its own versioned record. */
    public dev.friendai.agent.autonomy.FreeTimeService freeTime() {
        return freeTime;
    }

    /** Per-owner goal boards, backed by independent versioned records. */
    public GoalService goals() {
        return goals;
    }

    public GoalStore goalStore() {
        return goalStore;
    }

    public FreeTimeStore freeTimeStore() {
        return freeTimeStore;
    }

    public dev.friendai.infrastructure.persistence.routines.RoutineStore routines() {
        return routines;
    }

    public TerrainStore terrainStore() {
        return terrainStore;
    }

    public dev.friendai.features.inventory.minecraft.storage.StorageIndexService storage() {
        return storageService;
    }

    public TerrainSamplerService terrainSampler() {
        return terrainSampler;
    }

    /** Per-owner raised subjects and learned taste, backed by their own versioned record. */
    public SocialMemoryStore socialMemoryStore() {
        return socialMemoryStore;
    }

    /** Instruction skills stored for each companion and for the server. */
    public SkillService skills() {
        return skills;
    }

    /** Per-owner memory books: facts, trust and player records, learned language, and food rules. */
    public MemoryStore memory() {
        return memory;
    }

    /** Per-owner conversation transcripts, kept beside the memory books. */
    public TranscriptStore transcripts() {
        return transcripts;
    }

    /** Owner-authored companion identity and communication settings. */
    public CompanionProfileRepository profiles() {
        return profiles;
    }

    /** File-backed profile store, used by the server tick flush pipeline. */
    public CompanionProfileStore profileStore() {
        return profiles;
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

    public PlaceService placeService() {
        return placeService;
    }

    /** Per-owner record of the blocks the companion placed and may still be there. */
    public PlacedStore placed() {
        return placed;
    }

    public PlacedService placedService() {
        return placedService;
    }

    private PlaceSettings placeSettings() {
        var config = this.config.places;
        return new PlaceSettings(
                config.enabled,
                config.autoRemember,
                config.maxPlaces,
                config.mergeRadius,
                config.promptLimit,
                config.promptBudget);
    }
    /** Per-owner trader observations and informational order history. */
    public TradeStore trading() {
        return trading;
    }

    /** Owner-scoped trader observations for feature adapters. */
    public TradeBook tradeBook(UUID owner) {
        return trading.book(owner);
    }

    /** Current trading authority for one owner. */
    public TradeAuthority tradeAuthorityFor(UUID owner) {
        return sessions.tradeAuthorityFor(owner);
    }

    /** Immutable trading policy for feature adapters. */
    public TradingRuntimeSettings tradingSettings() {
        return settings().trading();
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

    @Override
    public List<dev.friendai.features.trading.TraderRecord> observeTraders(CompanionEntity companion) {
        return traderObserver.observe(companion);
    }

    @Override
    public UUID ownerUuid(CompanionEntity companion) {
        return companion.ownerUuid();
    }

    @Override
    public boolean ownerOnline(CompanionEntity companion) {
        return companion.owner().isPresent();
    }

    @Override
    public java.util.Optional<PlayerRef> findOnlinePlayer(String name) {
        return server.getPlayerList().getPlayers().stream()
                .filter(player -> player.getName().getString().equalsIgnoreCase(name))
                .findFirst()
                .map(player -> new PlayerRef(player.getUUID(), player.getName().getString()));
    }

    @Override
    public java.util.Optional<PlayerRecord> findKnownPlayer(UUID owner, String name) {
        return memory.book(owner).findByName(name);
    }

    @Override
    public boolean isTrusted(UUID owner, UUID target) {
        return memory.book(owner).isTrusted(target);
    }

    @Override
    public TrustRequest request(CompanionEntity companion, UUID target, String targetName, boolean trustPlayer) {
        var owner = companion.owner().orElseThrow(() -> new IllegalStateException("the owner is offline"));
        return trust.request(owner, target, targetName, trustPlayer);
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
            companion.executionCoordinator().suspend();
    }

    /** The name the owner gave their companion, or the configured default. */
    public String companionName(UUID owner) {
        return profiles.profile(owner).name() == null
                ? config.companion.name
                : profiles.profile(owner).name();
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
    public dev.friendai.features.knowledge.search.SearchHintStore searchHints() {
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
            companion.executionCoordinator().applySettings(applied);
        }
        sessions.reload(cfg);
        fileLog.update(cfg.logging.toSettings());
        sessions.debug().update(cfg.logging.toSettings());
        return problems;
    }

    /** A config name reaches companions that still use the default; a name the owner chose wins. */
    private void syncCompanionNames(ServerConfig cfg) {
        for (CompanionEntity companion : CompanionManager.get(server).all()) {
            UUID owner = companion.ownerUuid();
            if (owner != null && profiles.profile(owner).name() == null) {
                companion.applyName(cfg.companion.name);
            }
        }
    }

    private static void logProblems(List<String> problems) {
        problems.forEach(p -> FriendaiMod.LOGGER.warn("Config: {}", p));
    }

    /** One index line when this runtime closes, so a world deleted afterwards still leaves a trace. */
    void logWorldClose() {
        dev.friendai.debug.LogWorldIndex.append(
                logGameRoot,
                "world.close",
                logWorld,
                logWorldPath,
                logBoot,
                modVersion("friendai"),
                modVersion("minecraft"),
                System.currentTimeMillis());
    }

    private static String modVersion(String id) {
        return FabricLoader.getInstance()
                .getModContainer(id)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }
}
