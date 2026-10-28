package dev.friendai.server.runtime;

import dev.friendai.core.FriendaiServerState;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.net.DebugStream;
import dev.friendai.net.MemoryViewStream;
import dev.friendai.net.MovementDebugStream;
import dev.friendai.net.PlaceViewStream;
import dev.friendai.net.SkillsViewStream;
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
    }

    /** Starts runtime-only chunk restoration after the server has finished starting. */
    public void onServerStarted() {
        if (acceptingWork) state.chunkLoader().restore();
    }

    /** Called by the Fabric entity-load lifecycle delegate. */
    public void onEntityLoaded(Entity entity) {
        if (acceptingWork && entity instanceof CompanionEntity companion) {
            CompanionManager.get(server).register(companion);
            state.observations().register(companion);
        }
    }

    /** Called by the Fabric entity-unload lifecycle delegate, including during shutdown. */
    public void onEntityUnloaded(Entity entity) {
        if (entity instanceof CompanionEntity companion) {
            state.observations().unregister(companion);
            CompanionManager.get(server).unregister(companion);
        }
    }

    /** Applies owner-join state on the server thread. */
    public void onOwnerJoined(ServerPlayer player) {
        if (!acceptingWork) return;
        UUID owner = player.getUUID();
        state.sessions().onOwnerJoined(owner);
        state.memory().book(owner);
        if (state.memory().takeCorruptionNotice(owner)) {
            player.sendSystemMessage(Component.translatable("friendai.memory.corrupt"));
        }
    }

    /** Clears connection-scoped publishers and suspends the owner's agent work. */
    public void onOwnerLeft(ServerPlayer player) {
        DebugStream.onDisconnect(player);
        MovementDebugStream.onDisconnect(player);
        MemoryViewStream.onDisconnect(player);
        SkillsViewStream.onDisconnect(player);
        PlaceViewStream.onDisconnect(player);
        if (acceptingWork) state.sessions().onOwnerLeft(player.getUUID());
    }

    /** Runs the unchanged server tick pipeline until shutdown starts. */
    public void tick() {
        if (acceptingWork) tickWork.run();
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
            resources.close();
        }
    }
}
