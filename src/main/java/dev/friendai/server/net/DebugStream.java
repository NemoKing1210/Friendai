package dev.friendai.server.net;

import dev.friendai.FriendaiMod;
import dev.friendai.debug.DebugService;
import dev.friendai.features.cartography.TerrainGrid;
import dev.friendai.features.cartography.view.TerrainMapView;
import dev.friendai.foundation.diagnostics.LogEntry;
import dev.friendai.presentation.companion.CompanionSnapshot;
import dev.friendai.protocol.ProtocolFamily;
import dev.friendai.protocol.control.CompanionSnapshotProtocolCodec;
import dev.friendai.protocol.control.CompanionSnapshotView;
import dev.friendai.protocol.control.TerrainMapProtocolCodec;
import dev.friendai.server.runtime.ServerRuntime;
import dev.friendai.transport.protocol.CompanionSnapshotProtocolAdapter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Pushes snapshots to players who have the companion menu open, and holds their companion while it is open.
 *
 * <p>Every viewer is refreshed in rotation, at most one per server tick: several operators looking at their menus do
 * not rebuild every snapshot in the same tick, and one viewer keeps its ten-tick cadence. The terrain projection is
 * cached per viewer and rebuilt only when its grid generation or dimension changes, or the cache is a hundred ticks
 * old because its freshness flags age with the game time.
 */
public final class DebugStream {
    private static final int INTERVAL_TICKS = 10;
    /** A cached projection is refreshed this often at most, because its freshness flags age with the game time. */
    private static final int MAP_REBUILD_TICKS = 100;

    private long tick;
    private int rotation;
    private final Map<UUID, Long> lastPush = new HashMap<>();
    private final Map<UUID, MapCache> maps = new HashMap<>();

    /** One viewer's projection and the facts it was built from. */
    private record MapCache(String dimension, long generation, long builtTick, TerrainMapView view) {}

    /** Encodes one menu snapshot; the production value is the protocol codec, tests replace it. */
    public interface SnapshotEncoder {
        byte[] encode(CompanionSnapshotView snapshot, boolean operator, long requestId);
    }

    private final SnapshotEncoder encoder;

    public DebugStream() {
        this(CompanionSnapshotProtocolCodec::encodeForRecipient);
    }

    public DebugStream(SnapshotEncoder encoder) {
        this.encoder = encoder;
    }

    public void tick(ServerRuntime runtime) {
        MinecraftServer server = runtime.server();
        tick++;
        DebugService debug = runtime.sessions().debug();
        java.util.Set<UUID> subscribers = debug.subscribers();
        lastPush.keySet().retainAll(subscribers);
        maps.keySet().retainAll(subscribers);
        if (subscribers.isEmpty()) {
            return;
        }
        List<UUID> ordered = new ArrayList<>(subscribers);
        for (int offset = 0; offset < ordered.size(); offset++) {
            int index = Math.floorMod(rotation + offset, ordered.size());
            UUID owner = ordered.get(index);
            Long last = lastPush.get(owner);
            if (last != null && tick - last < INTERVAL_TICKS) {
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(owner);
            if (player == null) {
                closeMenu(runtime, owner);
                continue;
            }
            send(runtime, player);
            lastPush.put(owner, tick);
            rotation = index + 1;
            // One snapshot per tick bounds the menu work; the rotation serves the other viewers next.
            return;
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
        try {
            send(runtime, player, 0);
        } catch (RuntimeException failure) {
            // A snapshot that cannot be encoded is a bug in one frame, not a reason to stop the
            // server tick: close that viewer's menu and keep the rest of the world running.
            FriendaiMod.LOGGER.warn("companion snapshot failed for {}", player.getUUID(), failure);
            closeMenu(runtime, player.getUUID());
        }
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
                        encoder.encode(
                                CompanionSnapshotProtocolAdapter.toProtocol(snapshot),
                                Operators.isOperator(player),
                                requestId));

        runtime.protocolConnections()
                .sendView(
                        player,
                        ProtocolFamily.CONTROL,
                        TerrainMapProtocolCodec.encode(terrainMap(runtime, player, owner), requestId));
        lastPush.put(owner, tick);
    }

    /**
     * The viewer's projection. Its cells and marks only change when the owner's grid does, and its freshness flags age
     * slowly, so a push reuses the last build until the grid generation, the dimension or the freshness bucket moves.
     */
    private TerrainMapView terrainMap(ServerRuntime runtime, ServerPlayer player, UUID owner) {
        String dimension = player.level().dimension().identifier().toString();
        long now = player.level().getGameTime();
        TerrainGrid grid = runtime.terrainSampler().grid(owner);
        MapCache cached = maps.get(owner);
        if (cached != null
                && cached.dimension().equals(dimension)
                && cached.generation() == grid.generation()
                && now - cached.builtTick() < MAP_REBUILD_TICKS) {
            return cached.view();
        }
        TerrainMapView view = TerrainMapView.from(
                grid,
                dimension,
                owner.getMostSignificantBits() ^ grid.generation(),
                now,
                20L * 60L * 5L,
                TerrainMapView.MAX_CELLS);
        maps.put(owner, new MapCache(dimension, grid.generation(), now, view));
        return view;
    }
}
