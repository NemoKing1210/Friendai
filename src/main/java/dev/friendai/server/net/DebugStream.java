package dev.friendai.server.net;

import dev.friendai.debug.DebugService;
import dev.friendai.foundation.diagnostics.LogEntry;
import dev.friendai.presentation.companion.CompanionSnapshot;
import dev.friendai.protocol.ProtocolFamily;
import dev.friendai.protocol.control.CompanionSnapshotProtocolCodec;
import dev.friendai.server.runtime.ServerRuntime;
import dev.friendai.transport.protocol.CompanionSnapshotProtocolAdapter;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Pushes snapshots to players who have the companion menu open, and holds their companion while it is open. */
public final class DebugStream {
    private static final int INTERVAL_TICKS = 10;

    private int counter;

    public DebugStream() {}

    public void tick(ServerRuntime runtime) {
        MinecraftServer server = runtime.server();
        if (++counter % INTERVAL_TICKS != 0) {
            return;
        }
        DebugService debug = runtime.sessions().debug();
        for (UUID owner : debug.subscribers()) {
            ServerPlayer player = server.getPlayerList().getPlayer(owner);
            if (player == null) {
                closeMenu(runtime, owner);
                continue;
            }
            send(runtime, player);
        }
    }

    /** A client opened the companion menu: subscribe it and send the first snapshot. */
    public void openMenu(ServerRuntime runtime, ServerPlayer player, long requestId) {
        runtime.sessions().debug().subscribe(player.getUUID());
        try {
            sendNow(runtime, player, requestId);
        } catch (RuntimeException | Error failure) {
            closeMenu(runtime, player.getUUID());
            throw failure;
        }
    }

    /**
     * The menu closed — by the client, a disconnect or a lost player. Safe to call for a viewer that was never
     * subscribed.
     */
    public void closeMenu(ServerRuntime runtime, UUID viewer) {
        runtime.sessions().debug().unsubscribe(viewer);
    }

    /** Sends a snapshot now, if the player has the menu open. */
    public void sendNow(ServerRuntime runtime, ServerPlayer player, long requestId) {
        DebugService debug = runtime.sessions().debug();
        if (debug.subscribers().contains(player.getUUID())) {
            send(runtime, player, requestId);
        }
    }

    public void onDisconnect(ServerRuntime runtime, ServerPlayer player) {
        closeMenu(runtime, player.getUUID());
    }

    private void send(ServerRuntime runtime, ServerPlayer player) {
        send(runtime, player, 0);
    }

    private void send(ServerRuntime runtime, ServerPlayer player, long requestId) {
        DebugService debug = runtime.sessions().debug();
        UUID owner = player.getUUID();
        CompanionSnapshot snapshot = SnapshotBuilder.build(runtime, player, debug.lastSent(owner));
        List<LogEntry> log = snapshot.newLog();
        if (!log.isEmpty()) {
            debug.markSent(owner, log.get(log.size() - 1).id());
        }
        runtime.protocolConnections()
                .sendView(
                        player,
                        ProtocolFamily.CONTROL,
                        CompanionSnapshotProtocolCodec.encodeForRecipient(
                                CompanionSnapshotProtocolAdapter.toProtocol(snapshot),
                                Operators.isOperator(player),
                                requestId));
    }
}
