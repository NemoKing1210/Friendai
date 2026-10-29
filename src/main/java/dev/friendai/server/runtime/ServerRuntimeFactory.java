package dev.friendai.server.runtime;

import dev.friendai.FriendaiMod;
import dev.friendai.entity.CompanionManager;
import dev.friendai.server.trading.TraderRefreshService;
import net.minecraft.server.MinecraftServer;

/** Builds one complete per-server runtime graph and closes partial construction on failure. */
public final class ServerRuntimeFactory {

    private ServerRuntimeFactory() {}

    public static ServerRuntime create(MinecraftServer server) {
        RuntimeResources resources = new RuntimeResources(
                failure -> FriendaiMod.LOGGER.warn("Server runtime resource failed to close", failure));
        resources.onClose(() -> CompanionManager.close(server));
        return resources.construct(() -> {
            ServerServiceGraph services = new ServerServiceGraph(server, resources);
            TraderRefreshService traderRefresh = new TraderRefreshService(
                    server,
                    services.traderObserver(),
                    services.trading(),
                    () -> services.settings().trading());
            ServerTickPipeline tickPipeline = new ServerTickPipeline(server, services, traderRefresh);
            return new ServerRuntime(server, services, tickPipeline, resources);
        });
    }
}
