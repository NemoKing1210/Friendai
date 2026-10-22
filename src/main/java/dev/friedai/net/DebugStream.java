package dev.friedai.net;

import dev.friedai.core.FriedaiServerState;
import dev.friedai.debug.CompanionSnapshot;
import dev.friedai.debug.DebugService;
import dev.friedai.debug.LogEntry;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Pushes snapshots to players who have the companion menu open. */
public final class DebugStream {
    private static final int INTERVAL_TICKS = 10;
    private static int counter;

    private DebugStream() {}

    public static void tick(MinecraftServer server) {
        if (++counter % INTERVAL_TICKS != 0) {
            return;
        }
        DebugService debug = FriedaiServerState.get(server).brains().debug();
        for (UUID owner : debug.subscribers()) {
            ServerPlayer player = server.getPlayerList().getPlayer(owner);
            if (player == null) {
                debug.unsubscribe(owner);
                continue;
            }
            send(player);
        }
    }

    /** Sends a snapshot now, if the player has the menu open. */
    public static void sendNow(ServerPlayer player) {
        DebugService debug = FriedaiServerState.get(player.level().getServer()).brains().debug();
        if (debug.subscribers().contains(player.getUUID())) {
            send(player);
        }
    }

    public static void onDisconnect(ServerPlayer player) {
        FriedaiServerState.get(player.level().getServer()).brains().debug().unsubscribe(player.getUUID());
    }

    private static void send(ServerPlayer player) {
        DebugService debug = FriedaiServerState.get(player.level().getServer()).brains().debug();
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
