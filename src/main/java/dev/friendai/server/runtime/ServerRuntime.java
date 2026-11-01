package dev.friendai.server.runtime;

import dev.friendai.agent.autonomy.FreeTimeService;
import dev.friendai.agent.goal.GoalService;
import dev.friendai.entity.CompanionChunkLoader;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionLifeData;
import dev.friendai.entity.CompanionManager;
import dev.friendai.features.knowledge.search.SearchHintStore;
import dev.friendai.features.memory.CompanionProfileRepository;
import dev.friendai.features.memory.MemoryViewers;
import dev.friendai.features.memory.minecraft.tool.TrustAccess;
import dev.friendai.features.placements.minecraft.PlacedService;
import dev.friendai.features.places.minecraft.PlaceService;
import dev.friendai.features.trading.minecraft.tool.TradingToolAccess;
import dev.friendai.features.trading.minecraft.trading.BarterDropTracker;
import dev.friendai.features.trading.minecraft.trading.MerchantAdapter;
import dev.friendai.features.trading.minecraft.trading.MerchantLeases;
import dev.friendai.features.trading.minecraft.trading.PiglinBarterAdapter;
import dev.friendai.features.trading.minecraft.trading.TradeSessionGuard;
import dev.friendai.features.trading.minecraft.trading.TraderObserver;
import dev.friendai.infrastructure.config.ServerConfig;
import dev.friendai.infrastructure.persistence.appearance.SkinStore;
import dev.friendai.infrastructure.persistence.goals.GoalStore;
import dev.friendai.infrastructure.persistence.knowledge.KnowledgeStore;
import dev.friendai.infrastructure.persistence.memory.MemoryStore;
import dev.friendai.infrastructure.persistence.placements.PlacedStore;
import dev.friendai.infrastructure.persistence.places.PlaceStore;
import dev.friendai.infrastructure.persistence.trading.TradeStore;
import dev.friendai.perception.resource.ObservationScheduler;
import dev.friendai.perception.resource.ResourceCandidates;
import dev.friendai.server.agent.CompanionSessions;
import dev.friendai.server.configuration.ServerSettings;
import dev.friendai.server.execution.persistence.TaskPersistenceCoordinator;
import dev.friendai.server.memory.TrustConfirmations;
import dev.friendai.server.net.DebugStream;
import dev.friendai.server.net.MemoryViewStream;
import dev.friendai.server.net.MovementDebugStream;
import dev.friendai.server.net.PlaceViewStream;
import dev.friendai.server.net.ServerProtocolConnections;
import dev.friendai.server.net.SkillsViewStream;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/** One server's lifecycle owner and access point for its server-scoped services. */
public final class ServerRuntime implements AutoCloseable {

    private final MinecraftServer server;
    private final ServerServiceGraph services;
    private final Consumer<ServerRuntime> tickWork;
    private final Runnable stopWork;
    private final RuntimeResources resources;
    private final ServerProtocolConnections protocolConnections;
    private final CompanionManager companions;
    private final dev.friendai.server.agent.social.SocialDirector socialDirector =
            new dev.friendai.server.agent.social.SocialDirector();
    private final DebugStream debugStream;
    private final MovementDebugStream movementDebugStream;
    private final MemoryViewStream memoryViewStream;
    private final PlaceViewStream placeViewStream;
    private boolean acceptingWork = true;
    private boolean closed;

    ServerRuntime(
            MinecraftServer server,
            ServerServiceGraph services,
            ServerTickPipeline tickPipeline,
            RuntimeResources resources) {
        this(server, services, tickPipeline::tick, services::stopAcceptingWork, resources);
    }

    ServerRuntime(Runnable tickWork, Runnable stopWork, RuntimeResources resources) {
        this(null, null, ignored -> tickWork.run(), stopWork, resources);
    }

    private ServerRuntime(
            MinecraftServer server,
            ServerServiceGraph services,
            Consumer<ServerRuntime> tickWork,
            Runnable stopWork,
            RuntimeResources resources) {
        this.server = server;
        this.services = services;
        this.tickWork = tickWork;
        this.stopWork = stopWork;
        this.resources = resources;
        this.protocolConnections = server == null ? null : new ServerProtocolConnections(server);
        this.companions = server == null ? null : CompanionManager.get(server);
        if (companions != null) companions.attachRuntime(this);
        this.debugStream = new DebugStream();
        this.movementDebugStream = new MovementDebugStream();
        this.memoryViewStream = new MemoryViewStream();
        this.placeViewStream = new PlaceViewStream();
    }

    /** Starts runtime-only chunk restoration after the server has finished starting. */
    public void onServerStarted() {
        if (acceptingWork) services.chunkLoader().restore();
    }

    /** Called by the Fabric entity-load lifecycle delegate. */
    public void onEntityLoaded(Entity entity) {
        if (acceptingWork && entity instanceof CompanionEntity companion) {
            companions.register(companion);
            services.observations().register(companion);
        }
    }

    /** Called by the Fabric entity-unload lifecycle delegate, including during shutdown. */
    public void onEntityUnloaded(Entity entity) {
        if (entity instanceof CompanionEntity companion) {
            services.observations().unregister(companion);
            companions.unregister(companion);
        }
    }

    /** Applies owner-join data on the server thread. */
    public void onOwnerJoined(ServerPlayer player) {
        if (!acceptingWork) return;
        if (protocolConnections != null) protocolConnections.join(player);
        UUID owner = player.getUUID();
        services.sessions().onOwnerJoined(owner);
        services.memory().book(owner);
        if (services.memory().takeCorruptionNotice(owner)) {
            player.sendSystemMessage(Component.translatable("friendai.memory.corrupt"));
        }
    }

    /** Clears connection-scoped publishers and suspends the owner's agent work. */
    public void onOwnerLeft(ServerPlayer player) {
        if (protocolConnections != null) protocolConnections.leave(player);
        debugStream.onDisconnect(this, player);
        movementDebugStream.onDisconnect(this, player);
        memoryViewStream.onDisconnect(this, player);
        SkillsViewStream.onDisconnect(player);
        placeViewStream.onDisconnect(this, player);
        if (acceptingWork) services.sessions().onOwnerLeft(player.getUUID());
    }

    /** Runs the unchanged server tick pipeline until shutdown starts. */
    public void tick() {
        if (protocolConnections != null) protocolConnections.tick();
        if (acceptingWork) tickWork.accept(this);
    }

    /** The one director that decides when the companion may speak first. */
    public dev.friendai.server.agent.social.SocialDirector socialDirector() {
        return socialDirector;
    }

    public ServerProtocolConnections protocolConnections() {
        if (protocolConnections == null) throw new IllegalStateException("runtime has no server connection owner");
        return protocolConnections;
    }

    public MinecraftServer server() {
        if (server == null) throw new IllegalStateException("runtime has no server");
        return server;
    }

    public CompanionManager companions() {
        if (companions == null) throw new IllegalStateException("runtime has no companion manager");
        return companions;
    }

    public String companionName(UUID owner) {
        String name = profiles().profile(owner).name();
        return name == null ? config().companion.name : name;
    }

    public CompanionSessions sessions() {
        return services.sessions();
    }

    public FreeTimeService freeTime() {
        return services.freeTime();
    }

    public ResourceCandidates resourceCandidates() {
        return services.resources();
    }

    public KnowledgeStore knowledge() {
        return services.knowledge();
    }

    public SearchHintStore searchHints() {
        return services.searchHints();
    }

    public ObservationScheduler observations() {
        return services.observations();
    }

    public ServerSettings settings() {
        return services.settings();
    }

    public ServerConfig config() {
        return services.config();
    }

    public dev.friendai.debug.DiagnosticService diagnostics() {
        return services.diagnostics();
    }

    public CompanionLifeData companionLife() {
        return services.companionLife();
    }

    public GoalService goals() {
        return services.goals();
    }

    public GoalStore goalStore() {
        return services.goalStore();
    }

    public CompanionChunkLoader chunkLoader() {
        return services.chunkLoader();
    }

    public TaskPersistenceCoordinator taskPersistence() {
        return services.taskPersistence();
    }

    public MemoryStore memory() {
        return services.memory();
    }

    public dev.friendai.infrastructure.persistence.transcript.TranscriptStore transcripts() {
        return services.transcripts();
    }

    public MemoryViewers memoryViewers() {
        return services.memoryViewers();
    }

    public MemoryViewers placeViewers() {
        return services.placeViewers();
    }

    public MemoryViewStream memoryViewStream() {
        return memoryViewStream;
    }

    public PlaceViewStream placeViewStream() {
        return placeViewStream;
    }

    public DebugStream debugStream() {
        return debugStream;
    }

    public MovementDebugStream movementDebugStream() {
        return movementDebugStream;
    }

    public CompanionProfileRepository profiles() {
        return services.profiles();
    }

    public dev.friendai.server.instructions.SkillService skills() {
        return services.skills();
    }

    public PlaceStore places() {
        return services.places();
    }

    public PlaceService placeService() {
        return services.placeService();
    }

    public PlacedService placedService() {
        return services.placedService();
    }

    public PlacedStore placedStore() {
        return services.placed();
    }

    public TradeStore trading() {
        return services.trading();
    }

    public SkinStore skins() {
        return services.skins();
    }

    public TradingToolAccess tradingAccess() {
        return services;
    }

    public TrustAccess trustAccess() {
        return services;
    }

    public TrustConfirmations trust() {
        return services.trust();
    }

    public MerchantLeases merchantLeases() {
        return services.merchantLeases();
    }

    public TradeSessionGuard tradeSessionGuard() {
        return services.tradeSessionGuard();
    }

    public TraderObserver traderObserver() {
        return services.traderObserver();
    }

    public BarterDropTracker barterDropTracker() {
        return services.barterDropTracker();
    }

    public PiglinBarterAdapter piglinBarterAdapter() {
        return services.piglinBarterAdapter();
    }

    public MerchantAdapter merchantAdapter() {
        return services.merchantAdapter();
    }

    /** Records a barter drop captured at vanilla's item-spawn seam. */
    public void captureBarterOutput(UUID attempt, UUID piglin, UUID item, long tick) {
        if (acceptingWork) services.barterDropTracker().observeVanillaOutput(attempt, piglin, item, tick);
    }

    /** Applies a validated integrated-server config through the server-owned runtime boundary. */
    public void applyConfig(ServerConfig config) {
        if (acceptingWork) services.applyConfig(config);
    }

    /** Loads and applies the server config through its lifecycle owner. */
    public List<String> reloadConfig() {
        if (!acceptingWork) return List.of();
        return services.applyConfig(services.store().load());
    }

    /** Rejects future runtime work and cancels server-owned activity before stores are closed. */
    public synchronized void stopAcceptingWork() {
        if (!acceptingWork) return;
        acceptingWork = false;
        stopWork.run();
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            stopAcceptingWork();
        } finally {
            if (protocolConnections != null) protocolConnections.close();
            resources.close();
        }
    }
}
