package dev.friendai.server.net;

import dev.friendai.core.FriendaiServerState;
import dev.friendai.debug.DebugService;
import dev.friendai.entity.CompanionManager;
import dev.friendai.foundation.diagnostics.LogEntry;
import dev.friendai.presentation.companion.CompanionSnapshot;
import dev.friendai.protocol.ProtocolFamily;
import dev.friendai.protocol.control.CompanionSnapshotProtocolCodec;
import dev.friendai.server.runtime.ServerRuntimeRegistry;
import dev.friendai.transport.protocol.CompanionSnapshotProtocolAdapter;
import java.util.List;
import java.util.UUID;
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
        DebugService debug = FriendaiServerState.get(server).sessions().debug();
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
        openMenu(player, 0);
    }

    public static void openMenu(ServerPlayer player, long requestId) {
        var server = player.level().getServer();
        FriendaiServerState.get(server).sessions().debug().subscribe(player.getUUID());
        try {
            CompanionManager.get(server).find(player.getUUID()).ifPresent(companion -> companion.addMenuViewer());
            sendNow(player, requestId);
        } catch (RuntimeException | Error failure) {
            closeMenu(server, player.getUUID());
            throw failure;
        }
    }

    /**
     * The menu closed — by the client, a disconnect or a lost player — so the companion resumes whatever it was doing.
     * Safe to call for a viewer that was never subscribed.
     */
    public static void closeMenu(MinecraftServer server, UUID viewer) {
        FriendaiServerState.get(server).sessions().debug().unsubscribe(viewer);
        CompanionManager.get(server).find(viewer).ifPresent(companion -> companion.removeMenuViewer());
    }

    /** Sends a snapshot now, if the player has the menu open. */
    public static void sendNow(ServerPlayer player) {
        sendNow(player, 0);
    }

    public static void sendNow(ServerPlayer player, long requestId) {
        DebugService debug =
                FriendaiServerState.get(player.level().getServer()).sessions().debug();
        if (debug.subscribers().contains(player.getUUID())) {
            send(player, requestId);
        }
    }

    public static void onDisconnect(ServerPlayer player) {
        closeMenu(player.level().getServer(), player.getUUID());
    }

    private static void send(ServerPlayer player) {
        send(player, 0);
    }

    private static void send(ServerPlayer player, long requestId) {
        DebugService debug =
                FriendaiServerState.get(player.level().getServer()).sessions().debug();
        UUID owner = player.getUUID();
        CompanionSnapshot snapshot = SnapshotBuilder.build(player, debug.lastSent(owner));
        List<LogEntry> log = snapshot.newLog();
        if (!log.isEmpty()) {
            debug.markSent(owner, log.get(log.size() - 1).id());
        }
        ServerRuntimeRegistry.getOrCreate(player.level().getServer())
                .protocolConnections()
                .sendView(
                        player,
                        ProtocolFamily.CONTROL,
                        CompanionSnapshotProtocolCodec.encodeForRecipient(
                                CompanionSnapshotProtocolAdapter.toProtocol(snapshot),
                                Operators.isOperator(player),
                                requestId));
    }
}
