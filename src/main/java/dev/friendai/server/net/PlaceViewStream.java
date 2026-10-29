package dev.friendai.server.net;

import dev.friendai.features.memory.MenuStatus;
import dev.friendai.features.places.PlaceRegistry;
import dev.friendai.features.places.PlaceView;
import dev.friendai.features.places.PlaceViewBuilder;
import dev.friendai.protocol.ProtocolFamily;
import dev.friendai.protocol.places.PlaceProtocolViewCodec;
import dev.friendai.server.runtime.ServerRuntime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Pushes the places view to players who have the tab open: on demand with a status, and on changes. */
public final class PlaceViewStream {
    private static final int INTERVAL_TICKS = 10;
    private int counter;
    private int viewEpoch;
    private int lastConfig;

    public PlaceViewStream() {}

    /** Registers the viewer and sends the current view immediately. */
    public void open(ServerRuntime runtime, ServerPlayer player) {
        open(runtime, player, 0);
    }

    public void open(ServerRuntime runtime, ServerPlayer player, long requestId) {
        runtime.placeViewers().open(player.getUUID());
        sendNow(runtime, player, Optional.empty(), requestId);
    }

    public void close(ServerRuntime runtime, ServerPlayer player) {
        runtime.placeViewers().close(player.getUUID());
    }

    /** Sends a fresh view carrying the result of a request, if the player has the tab open. */
    public void sendNow(ServerRuntime runtime, ServerPlayer player, Optional<MenuStatus> status) {
        sendNow(runtime, player, status, 0);
    }

    public void sendNow(ServerRuntime runtime, ServerPlayer player, Optional<MenuStatus> status, long requestId) {
        if (runtime.placeViewers().isOpen(player.getUUID())) {
            send(runtime, player, status, requestId);
        }
    }

    /** Every {@link #INTERVAL_TICKS} ticks, sends a status-free view to viewers whose book changed. */
    public void tick(ServerRuntime runtime) {
        MinecraftServer server = runtime.server();
        if (++counter % INTERVAL_TICKS != 0) {
            return;
        }
        var viewers = runtime.placeViewers();
        int epoch = currentEpoch(runtime);
        for (UUID uuid : viewers.viewers()) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player == null) {
                viewers.close(uuid);
                continue;
            }
            if (viewers.needsUpdate(uuid, runtime.places().book(uuid).revision(), epoch)) {
                send(runtime, player, Optional.empty(), 0);
            }
        }
    }

    /**
     * Bumps the view epoch when something the view shows but the book does not record changed: the places config
     * that decides what the tab may show. The registry revision covers every stored place.
     */
    private int currentEpoch(ServerRuntime runtime) {
        int config = Objects.hash(
                runtime.config().places.enabled,
                runtime.config().places.maxPlaces,
                runtime.config().places.mergeRadius);
        if (config != lastConfig) {
            lastConfig = config;
            viewEpoch++;
        }
        return viewEpoch;
    }

    public void onDisconnect(ServerRuntime runtime, ServerPlayer player) {
        runtime.placeViewers().close(player.getUUID());
    }

    private void send(ServerRuntime runtime, ServerPlayer player, Optional<MenuStatus> status, long requestId) {
        UUID uuid = player.getUUID();
        PlaceRegistry book = runtime.places().book(uuid);
        PlaceView view = PlaceViewBuilder.build(book, runtime.config().places.enabled, status);
        runtime.protocolConnections()
                .sendView(player, ProtocolFamily.PLACES, PlaceProtocolViewCodec.encodeFitting(view, requestId));
        runtime.placeViewers().markSent(uuid, book.revision(), viewEpoch);
    }
}
