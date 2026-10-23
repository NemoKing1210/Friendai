package dev.friendai.net;

import dev.friendai.core.FriendaiServerState;
import dev.friendai.memory.MemoryBook;
import dev.friendai.memory.MemoryView;
import dev.friendai.memory.MemoryViewBuilder;
import dev.friendai.memory.MemoryViewCodec;
import dev.friendai.memory.MemoryViewers;
import dev.friendai.memory.MenuStatus;
import dev.friendai.memory.PlayerRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Pushes the memory view to players who have the tab open: on demand with a status, and on changes. */
public final class MemoryViewStream {
    private static final int INTERVAL_TICKS = 10;
    private static int counter;

    private MemoryViewStream() {}

    /** Registers the viewer and sends the current view immediately. */
    public static void open(ServerPlayer player) {
        FriendaiServerState.get(player.level().getServer()).memoryViewers().open(player.getUUID());
        sendNow(player, Optional.empty());
    }

    public static void close(ServerPlayer player) {
        FriendaiServerState.get(player.level().getServer()).memoryViewers().close(player.getUUID());
    }

    /** Sends a fresh view carrying the result of a request, if the player has the tab open. */
    public static void sendNow(ServerPlayer player, Optional<MenuStatus> status) {
        FriendaiServerState state = FriendaiServerState.get(player.level().getServer());
        if (state.memoryViewers().isOpen(player.getUUID())) {
            send(state, player, status);
        }
    }

    /** Every {@link #INTERVAL_TICKS} ticks, sends a status-free view to viewers whose book changed. */
    public static void tick(MinecraftServer server) {
        if (++counter % INTERVAL_TICKS != 0) {
            return;
        }
        FriendaiServerState state = FriendaiServerState.get(server);
        MemoryViewers viewers = state.memoryViewers();
        for (UUID uuid : viewers.viewers()) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player == null) {
                viewers.close(uuid);
                continue;
            }
            if (viewers.needsUpdate(uuid, state.memory().book(uuid).revision())) {
                send(state, player, Optional.empty());
            }
        }
    }

    public static void onDisconnect(ServerPlayer player) {
        FriendaiServerState.get(player.level().getServer()).memoryViewers().close(player.getUUID());
    }

    private static void send(FriendaiServerState state, ServerPlayer player, Optional<MenuStatus> status) {
        UUID uuid = player.getUUID();
        MemoryBook book = state.memory().book(uuid);
        List<PlayerRef> online = new ArrayList<>();
        for (ServerPlayer other : player.level().getServer().getPlayerList().getPlayers()) {
            online.add(new PlayerRef(other.getUUID(), other.getName().getString()));
        }
        MemoryView view = MemoryViewBuilder.build(
                book,
                uuid,
                player.getName().getString(),
                online,
                state.config().memory.enabled,
                state.config().memory.limits(),
                status);
        if (ServerPlayNetworking.canSend(player, MemoryViewS2CPayload.TYPE)) {
            ServerPlayNetworking.send(player, new MemoryViewS2CPayload(MemoryViewCodec.encode(view)));
        }
        state.memoryViewers().markSent(uuid, book.revision());
    }
}
