package dev.friendai.server.runtime;

import dev.friendai.core.FriendaiServerState;
import dev.friendai.entity.CompanionManager;
import dev.friendai.features.trading.minecraft.TraderRefreshService;
import dev.friendai.net.DebugStream;
import dev.friendai.net.MemoryViewStream;
import dev.friendai.net.MovementDebugStream;
import dev.friendai.net.PlaceViewStream;
import dev.friendai.net.SkillsViewStream;
import net.minecraft.server.MinecraftServer;

/** Preserves the existing END_SERVER_TICK order and each service's own cadence. */
public final class ServerTickPipeline {

    private final MinecraftServer server;
    private final FriendaiServerState state;
    private final TraderRefreshService traderRefresh;

    public ServerTickPipeline(MinecraftServer server, FriendaiServerState state, TraderRefreshService traderRefresh) {
        this.server = server;
        this.state = state;
        this.traderRefresh = traderRefresh;
    }

    public void tick() {
        long gameTime = server.overworld().getGameTime();
        state.barterDropTracker().expire(gameTime);
        state.tradeSessionGuard().tick(gameTime);
        traderRefresh.tick();
        state.observations().tick(server, gameTime);
        state.sessions().tick();
        state.chunkLoader().tick();
        CompanionManager.get(server).tick();
        state.tick(System.currentTimeMillis());
        state.memory().flushIfDue();
        state.places().flushIfDue();
        state.placed().flushIfDue();
        state.trading().flushIfDue();
        DebugStream.tick(server);
        MemoryViewStream.tick(server);
        SkillsViewStream.tick(server);
        PlaceViewStream.tick(server);
        MovementDebugStream.tick(server);
    }
}
