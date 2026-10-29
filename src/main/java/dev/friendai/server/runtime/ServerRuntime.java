package dev.friendai.server.runtime;

import dev.friendai.agent.autonomy.FreeTimeService;
import dev.friendai.agent.goal.GoalService;
import dev.friendai.core.FriendaiServerState;
import dev.friendai.core.TrustConfirmations;
import dev.friendai.entity.CompanionChunkLoader;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionLifeData;
import dev.friendai.entity.CompanionManager;
import dev.friendai.features.knowledge.search.SearchHintStore;
import dev.friendai.features.memory.CompanionProfileRepository;
import dev.friendai.features.memory.MemoryViewers;
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
import dev.friendai.infrastructure.persistence.knowledge.KnowledgeStore;
import dev.friendai.infrastructure.persistence.memory.MemoryStore;
import dev.friendai.infrastructure.persistence.places.PlaceStore;
import dev.friendai.infrastructure.persistence.trading.TradeStore;
import dev.friendai.perception.resource.ObservationScheduler;
import dev.friendai.perception.resource.ResourceCandidates;
import dev.friendai.server.agent.CompanionSessions;
import dev.friendai.server.configuration.ServerSettings;
import dev.friendai.server.execution.persistence.TaskPersistenceCoordinator;
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

/** One server's lifecycle owner and the temporary bridge to its existing services. */
public final class ServerRuntime implements AutoCloseable {

    private final MinecraftServer server;
    private final FriendaiServerState state;
    private final Consumer<ServerRuntime> tickWork;
    private final Runnable stopWork;
    private final RuntimeResources resources;
    private final ServerProtocolConnections protocolConnections;
    private final CompanionManager companions;
    private final DebugStream debugStream;
    private final MovementDebugStream movementDebugStream;
    private final MemoryViewStream memoryViewStream;
    private final PlaceViewStream placeViewStream;
    private boolean acceptingWork = true;
    private boolean closed;

    public ServerRuntime(
            MinecraftServer server,
            FriendaiServerState state,
            ServerTickPipeline tickPipeline,
            RuntimeResources resources) {
        this(server, state, tickPipeline::tick, state::stopAcceptingWork, resources);
    }

    ServerRuntime(Runnable tickWork, Runnable stopWork, RuntimeResources resources) {
        this(null, null, ignored -> tickWork.run(), stopWork, resources);
    }

    private ServerRuntime(
            MinecraftServer server,
            FriendaiServerState state,
            Consumer<ServerRuntime> tickWork,
            Runnable stopWork,
            RuntimeResources resources) {
        this.server = server;
        this.state = state;
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
        if (acceptingWork) state.chunkLoader().restore();
    }

    /** Called by the Fabric entity-load lifecycle delegate. */
    public void onEntityLoaded(Entity entity) {
        if (acceptingWork && entity instanceof CompanionEntity companion) {
            companions.register(companion);
            state.observations().register(companion);
        }
    }

    /** Called by the Fabric entity-unload lifecycle delegate, including during shutdown. */
    public void onEntityUnloaded(Entity entity) {
        if (entity instanceof CompanionEntity companion) {
            state.observations().unregister(companion);
            companions.unregister(companion);
        }
    }

    /** Applies owner-join state on the server thread. */
    public void onOwnerJoined(ServerPlayer player) {
        if (!acceptingWork) return;
        if (protocolConnections != null) protocolConnections.join(player);
        UUID owner = player.getUUID();
        state.sessions().onOwnerJoined(owner);
        state.memory().book(owner);
        if (state.memory().takeCorruptionNotice(owner)) {
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
        if (acceptingWork) state.sessions().onOwnerLeft(player.getUUID());
    }

    /** Runs the unchanged server tick pipeline until shutdown starts. */
    public void tick() {
        if (protocolConnections != null) protocolConnections.tick();
        if (acceptingWork) tickWork.accept(this);
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
        return state.sessions();
    }

    public FreeTimeService freeTime() {
        return state.freeTime();
    }

    public ResourceCandidates resourceCandidates() {
        return state.resources();
    }

    public KnowledgeStore knowledge() {
        return state.knowledge();
    }

    public SearchHintStore searchHints() {
        return state.searchHints();
    }

    public ObservationScheduler observations() {
        return state.observations();
    }

    public ServerSettings settings() {
        return state.settings();
    }

    public ServerConfig config() {
        return state.config();
    }

    public CompanionLifeData companionLife() {
        return state.companionLife();
    }

    public GoalService goals() {
        return state.goals();
    }

    public CompanionChunkLoader chunkLoader() {
        return state.chunkLoader();
    }

    public TaskPersistenceCoordinator taskPersistence() {
        return state.taskPersistence();
    }

    public MemoryStore memory() {
        return state.memory();
    }

    public MemoryViewers memoryViewers() {
        return state.memoryViewers();
    }

    public MemoryViewers placeViewers() {
        return state.placeViewers();
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
        return state.profiles();
    }

    public dev.friendai.core.SkillService skills() {
        return state.skills();
    }

    public PlaceStore places() {
        return state.places();
    }

    public PlaceService placeService() {
        return state.placeService();
    }

    public PlacedService placedService() {
        return state.placedService();
    }

    public TradeStore trading() {
        return state.trading();
    }

    public SkinStore skins() {
        return state.skins();
    }

    public TradingToolAccess tradingAccess() {
        return state;
    }

    public TrustConfirmations trust() {
        return state.trust();
    }

    public MerchantLeases merchantLeases() {
        return state.merchantLeases();
    }

    public TradeSessionGuard tradeSessionGuard() {
        return state.tradeSessionGuard();
    }

    public TraderObserver traderObserver() {
        return state.traderObserver();
    }

    public BarterDropTracker barterDropTracker() {
        return state.barterDropTracker();
    }

    public PiglinBarterAdapter piglinBarterAdapter() {
        return state.piglinBarterAdapter();
    }

    public MerchantAdapter merchantAdapter() {
        return state.merchantAdapter();
    }

    /** Records a barter drop captured at vanilla's item-spawn seam. */
    public void captureBarterOutput(UUID attempt, UUID piglin, UUID item, long tick) {
        if (acceptingWork) state.barterDropTracker().observeVanillaOutput(attempt, piglin, item, tick);
    }

    /** Applies a validated integrated-server config through the server-owned runtime boundary. */
    public void applyConfig(ServerConfig config) {
        if (acceptingWork) state.applyConfig(config);
    }

    /** Loads and applies the server config through its lifecycle owner. */
    public List<String> reloadConfig() {
        if (!acceptingWork) return List.of();
        return state.applyConfig(state.store().load());
    }

    /** Rejects future runtime work and cancels server-owned activity before stores are closed. */
    public synchronized void stopAcceptingWork() {
        if (!acceptingWork) return;
        acceptingWork = false;
        stopWork.run();
    }

    /** Temporary bridge for callers being migrated to explicit services in later plan tasks. */
    public FriendaiServerState state() {
        return state;
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
