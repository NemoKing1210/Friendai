package dev.friendai.server.net;

import dev.friendai.features.memory.MemoryBook;
import dev.friendai.features.memory.MemoryView;
import dev.friendai.features.memory.MemoryViewBuilder;
import dev.friendai.features.memory.MenuStatus;
import dev.friendai.features.memory.PlayerRef;
import dev.friendai.protocol.ProtocolFamily;
import dev.friendai.protocol.memory.MemoryProtocolViewCodec;
import dev.friendai.server.runtime.ServerRuntime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Pushes the memory view to players who have the tab open: on demand with a status, and on changes. */
public final class MemoryViewStream {
    private static final int INTERVAL_TICKS = 10;
    private int counter;
    private int viewEpoch;
    private List<UUID> lastOnline = List.of();
    private int lastConfig;

    public MemoryViewStream() {}

    /** Registers the viewer and sends the current view immediately. */
    public void open(ServerRuntime runtime, ServerPlayer player) {
        open(runtime, player, 0);
    }

    public void open(ServerRuntime runtime, ServerPlayer player, long requestId) {
        runtime.memoryViewers().open(player.getUUID());
        sendNow(runtime, player, Optional.empty(), requestId);
    }

    public void close(ServerRuntime runtime, ServerPlayer player) {
        runtime.memoryViewers().close(player.getUUID());
    }

    /** Sends a fresh view carrying the result of a request, if the player has the tab open. */
    public void sendNow(ServerRuntime runtime, ServerPlayer player, Optional<MenuStatus> status) {
        sendNow(runtime, player, status, 0);
    }

    public void sendNow(ServerRuntime runtime, ServerPlayer player, Optional<MenuStatus> status, long requestId) {
        if (runtime.memoryViewers().isOpen(player.getUUID())) {
            send(runtime, player, status, requestId);
        }
    }

    /** Every {@link #INTERVAL_TICKS} ticks, sends a status-free view to viewers whose book changed. */
    public void tick(ServerRuntime runtime) {
        MinecraftServer server = runtime.server();
        if (++counter % INTERVAL_TICKS != 0) {
            return;
        }
        var viewers = runtime.memoryViewers();
        int epoch = currentEpoch(runtime, server);
        for (UUID uuid : viewers.viewers()) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player == null) {
                viewers.close(uuid);
                continue;
            }
            if (viewers.needsUpdate(uuid, runtime.memory().book(uuid).revision(), epoch)) {
                send(runtime, player, Optional.empty(), 0);
            }
        }
    }

    /**
     * Bumps the view epoch when something the view shows but the book does not record changed: who is
     * online (names and the online flag, and guests that stop being listed) or the memory config.
     */
    private int currentEpoch(ServerRuntime runtime, MinecraftServer server) {
        List<UUID> online = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            online.add(player.getUUID());
        }
        online.sort(null);
        int config = Objects.hash(
                runtime.config().memory.enabled,
                runtime.config().memory.limits().maxFactChars(),
                runtime.config().memory.limits().maxFactsPerPlayer(),
                runtime.config().memory.limits().maxWorldFacts());
        if (!online.equals(lastOnline) || config != lastConfig) {
            lastOnline = List.copyOf(online);
            lastConfig = config;
            viewEpoch++;
        }
        return viewEpoch;
    }

    public void onDisconnect(ServerRuntime runtime, ServerPlayer player) {
        runtime.memoryViewers().close(player.getUUID());
    }

    private void send(ServerRuntime runtime, ServerPlayer player, Optional<MenuStatus> status, long requestId) {
        UUID uuid = player.getUUID();
        MemoryBook book = runtime.memory().book(uuid);
        List<PlayerRef> online = new ArrayList<>();
        for (ServerPlayer other : player.level().getServer().getPlayerList().getPlayers()) {
            online.add(new PlayerRef(other.getUUID(), other.getName().getString()));
        }
        MemoryView view = MemoryViewBuilder.build(
                book,
                uuid,
                player.getName().getString(),
                online,
                runtime.config().memory.enabled,
                runtime.config().memory.limits(),
                status,
                runtime.profiles().profile(uuid),
                runtime.config().companion.description);
        runtime.protocolConnections()
                .sendView(player, ProtocolFamily.MEMORY, MemoryProtocolViewCodec.encodeFitting(view, requestId));
        runtime.memoryViewers()
                .markSent(uuid, book.revision() + runtime.profiles().revision(uuid), viewEpoch);
    }
}
