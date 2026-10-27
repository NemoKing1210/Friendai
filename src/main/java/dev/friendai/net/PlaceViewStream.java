package dev.friendai.net;

import dev.friendai.core.FriendaiServerState;
import dev.friendai.memory.MemoryViewers;
import dev.friendai.memory.MenuStatus;
import dev.friendai.place.PlaceRegistry;
import dev.friendai.place.PlaceView;
import dev.friendai.place.PlaceViewBuilder;
import dev.friendai.place.PlaceViewCodec;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Pushes the places view to players who have the tab open: on demand with a status, and on changes. */
public final class PlaceViewStream {
    private static final int INTERVAL_TICKS = 10;
    private static int counter;
    private static int viewEpoch;
    private static int lastConfig;

    private PlaceViewStream() {}

    /** Registers the viewer and sends the current view immediately. */
    public static void open(ServerPlayer player) {
        FriendaiServerState.get(player.level().getServer()).placeViewers().open(player.getUUID());
        sendNow(player, Optional.empty());
    }

    public static void close(ServerPlayer player) {
        FriendaiServerState.get(player.level().getServer()).placeViewers().close(player.getUUID());
    }

    /** Sends a fresh view carrying the result of a request, if the player has the tab open. */
    public static void sendNow(ServerPlayer player, Optional<MenuStatus> status) {
        FriendaiServerState state = FriendaiServerState.get(player.level().getServer());
        if (state.placeViewers().isOpen(player.getUUID())) {
            send(state, player, status);
        }
    }

    /** Every {@link #INTERVAL_TICKS} ticks, sends a status-free view to viewers whose book changed. */
    public static void tick(MinecraftServer server) {
        if (++counter % INTERVAL_TICKS != 0) {
            return;
        }
        FriendaiServerState state = FriendaiServerState.get(server);
        MemoryViewers viewers = state.placeViewers();
        int epoch = currentEpoch(state);
        for (UUID uuid : viewers.viewers()) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player == null) {
                viewers.close(uuid);
                continue;
            }
            if (viewers.needsUpdate(uuid, state.places().book(uuid).revision(), epoch)) {
                send(state, player, Optional.empty());
            }
        }
    }

    /**
     * Bumps the view epoch when something the view shows but the book does not record changed: the places config
     * that decides what the tab may show. The registry revision covers every stored place.
     */
    private static int currentEpoch(FriendaiServerState state) {
        int config = Objects.hash(
                state.config().places.enabled,
                state.config().places.maxPlaces,
                state.config().places.mergeRadius);
        if (config != lastConfig) {
            lastConfig = config;
            viewEpoch++;
        }
        return viewEpoch;
    }

    public static void onDisconnect(ServerPlayer player) {
        FriendaiServerState.get(player.level().getServer()).placeViewers().close(player.getUUID());
    }

    private static void send(FriendaiServerState state, ServerPlayer player, Optional<MenuStatus> status) {
        UUID uuid = player.getUUID();
        PlaceRegistry book = state.places().book(uuid);
        PlaceView view = PlaceViewBuilder.build(book, state.config().places, status);
        if (ServerPlayNetworking.canSend(player, PlacesViewS2CPayload.TYPE)) {
            ServerPlayNetworking.send(player, new PlacesViewS2CPayload(
                    PlaceViewCodec.encodeFitting(view, PlacesViewS2CPayload.MAX_BYTES)));
        }
        state.placeViewers().markSent(uuid, book.revision(), viewEpoch);
    }
}
