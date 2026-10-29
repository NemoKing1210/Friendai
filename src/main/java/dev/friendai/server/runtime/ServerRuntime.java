package dev.friendai.server.runtime;

import dev.friendai.core.FriendaiServerState;
import dev.friendai.core.TrustConfirmations;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionLifeData;
import dev.friendai.entity.CompanionManager;
import dev.friendai.features.memory.CompanionProfileRepository;
import dev.friendai.features.trading.minecraft.tool.TradingToolAccess;
import dev.friendai.infrastructure.config.ServerConfig;
import dev.friendai.infrastructure.persistence.appearance.SkinStore;
import dev.friendai.infrastructure.persistence.memory.MemoryStore;
import dev.friendai.infrastructure.persistence.places.PlaceStore;
import dev.friendai.infrastructure.persistence.trading.TradeStore;
import dev.friendai.server.agent.CompanionSessions;
import dev.friendai.server.net.DebugStream;
import dev.friendai.server.net.MemoryViewStream;
import dev.friendai.server.net.MovementDebugStream;
import dev.friendai.server.net.PlaceViewStream;
import dev.friendai.server.net.ServerProtocolConnections;
import dev.friendai.server.net.SkillsViewStream;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/** One server's lifecycle owner and the temporary bridge to its existing services. */
public final class ServerRuntime implements AutoCloseable {

    private final MinecraftServer server;
    private final FriendaiServerState state;
    private final Runnable tickWork;
    private final Runnable stopWork;
    private final RuntimeResources resources;
    private final ServerProtocolConnections protocolConnections;
    private final CompanionManager companions;
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
        this(null, null, tickWork, stopWork, resources);
    }

    private ServerRuntime(
            MinecraftServer server,
            FriendaiServerState state,
            Runnable tickWork,
            Runnable stopWork,
            RuntimeResources resources) {
        this.server = server;
        this.state = state;
        this.tickWork = tickWork;
        this.stopWork = stopWork;
        this.resources = resources;
        this.protocolConnections = server == null ? null : new ServerProtocolConnections(server);
        this.companions = server == null ? null : CompanionManager.get(server);
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
        DebugStream.onDisconnect(player);
        MovementDebugStream.onDisconnect(player);
        MemoryViewStream.onDisconnect(player);
        SkillsViewStream.onDisconnect(player);
        PlaceViewStream.onDisconnect(player);
        if (acceptingWork) state.sessions().onOwnerLeft(player.getUUID());
    }

    /** Runs the unchanged server tick pipeline until shutdown starts. */
    public void tick() {
        if (protocolConnections != null) protocolConnections.tick();
        if (acceptingWork) tickWork.run();
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

    public CompanionSessions sessions() {
        return state.sessions();
    }

    public ServerConfig config() {
        return state.config();
    }

    public CompanionLifeData companionLife() {
        return state.companionLife();
    }

    public MemoryStore memory() {
        return state.memory();
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

    /** Applies a validated integrated-server config through the server-owned runtime boundary. */
    public void applyConfig(ServerConfig config) {
        if (acceptingWork) state.applyConfig(config);
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
