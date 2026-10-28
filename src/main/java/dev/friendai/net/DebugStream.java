package dev.friendai.net;

import dev.friendai.core.FriendaiServerState;
import dev.friendai.debug.CompanionSnapshot;
import dev.friendai.debug.DebugService;
import dev.friendai.debug.LogEntry;
import dev.friendai.entity.CompanionManager;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Pushes snapshots to players who have the companion menu open, and holds their companion while it is open. */
public final class DebugStream {
    private static final int INTERVAL_TICKS = 10;
    private static int counter;

    private DebugStream() {}

    public static void tick(MinecraftServer server) {
        if (++counter % INTERVAL_TICKS != 0) {
            return;
        }
        DebugService debug = FriendaiServerState.get(server).brains().debug();
        for (UUID owner : debug.subscribers()) {
            ServerPlayer player = server.getPlayerList().getPlayer(owner);
            if (player == null) {
                closeMenu(server, owner);
                continue;
            }
            send(player);
        }
    }

    /** A client opened the companion menu: subscribe it, pause that player's companion and send the first snapshot. */
    public static void openMenu(ServerPlayer player) {
        FriendaiServerState.get(player.level().getServer()).brains().debug().subscribe(player.getUUID());
        CompanionManager.get(player.level().getServer())
                .find(player.getUUID())
                .ifPresent(companion -> companion.addMenuViewer());
        sendNow(player);
    }

    /**
     * The menu closed — by the client, a disconnect or a lost player — so the companion resumes whatever it was doing.
     * Safe to call for a viewer that was never subscribed.
     */
    public static void closeMenu(MinecraftServer server, UUID viewer) {
        FriendaiServerState.get(server).brains().debug().unsubscribe(viewer);
        CompanionManager.get(server).find(viewer).ifPresent(companion -> companion.removeMenuViewer());
    }

    /** Sends a snapshot now, if the player has the menu open. */
    public static void sendNow(ServerPlayer player) {
        DebugService debug =
                FriendaiServerState.get(player.level().getServer()).brains().debug();
        if (debug.subscribers().contains(player.getUUID())) {
            send(player);
        }
    }

    public static void onDisconnect(ServerPlayer player) {
        closeMenu(player.level().getServer(), player.getUUID());
    }

    private static void send(ServerPlayer player) {
        DebugService debug =
                FriendaiServerState.get(player.level().getServer()).brains().debug();
        UUID owner = player.getUUID();
        CompanionSnapshot snapshot = SnapshotBuilder.build(player, debug.lastSent(owner));
        List<LogEntry> log = snapshot.newLog();
        if (!log.isEmpty()) {
            debug.markSent(owner, log.get(log.size() - 1).id());
        }
        if (ServerPlayNetworking.canSend(player, CompanionSnapshotS2CPayload.TYPE)) {
            ServerPlayNetworking.send(player, new CompanionSnapshotS2CPayload(snapshot));
        }
    }
}
