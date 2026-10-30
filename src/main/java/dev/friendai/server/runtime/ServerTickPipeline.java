package dev.friendai.server.runtime;

import dev.friendai.entity.CompanionManager;
import dev.friendai.server.net.SkillsViewStream;
import dev.friendai.server.trading.TraderRefreshService;
import net.minecraft.server.MinecraftServer;

/** Preserves the existing END_SERVER_TICK order and each service's own cadence. */
public final class ServerTickPipeline {

    private final MinecraftServer server;
    private final ServerServiceGraph services;
    private final TraderRefreshService traderRefresh;
    private final OwnerRecordFlush ownerRecordFlush;

    ServerTickPipeline(MinecraftServer server, ServerServiceGraph services, TraderRefreshService traderRefresh) {
        this.server = server;
        this.services = services;
        this.traderRefresh = traderRefresh;
        this.ownerRecordFlush =
                new OwnerRecordFlush(services.profileStore(), services.goalStore(), services.freeTimeStore());
    }

    public void tick(ServerRuntime runtime) {
        long gameTime = server.overworld().getGameTime();
        services.barterDropTracker().expire(gameTime);
        services.tradeSessionGuard().tick(gameTime);
        traderRefresh.tick();
        services.observations().tick(server, gameTime);
        services.sessions().tick();
        services.chunkLoader().tick();
        CompanionManager.get(server).tick();
        services.tick(System.currentTimeMillis());
        services.memory().flushIfDue();
        services.transcripts().flushIfDue();
        services.knowledge().flushIfDue();
        services.places().flushIfDue();
        services.placed().flushIfDue();
        services.trading().flushIfDue();
        ownerRecordFlush.tick();
        runtime.debugStream().tick(runtime);
        runtime.memoryViewStream().tick(runtime);
        SkillsViewStream.tick(runtime);
        runtime.placeViewStream().tick(runtime);
        runtime.movementDebugStream().tick(runtime);
    }
}
