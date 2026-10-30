package dev.friendai.server.net;

import dev.friendai.FriendaiMod;
import dev.friendai.foundation.limits.RateLimiter;
import dev.friendai.protocol.DecodeResult;
import dev.friendai.protocol.DecodedClientRequest;
import dev.friendai.protocol.ProtocolBootstrapCodec;
import dev.friendai.protocol.ProtocolCapabilities;
import dev.friendai.protocol.ProtocolFamily;
import dev.friendai.protocol.ProtocolServerRequestGate;
import dev.friendai.protocol.ProtocolVersion;
import dev.friendai.transport.payload.ProtocolAcceptC2SPayload;
import dev.friendai.transport.payload.ProtocolHelloS2CPayload;
import dev.friendai.transport.payload.ProtocolViewS2CPayload;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Owns handshake, capability, request sequence, and rate-limit state for one server's player connections. */
public final class ServerProtocolConnections implements AutoCloseable {
    public record AdmittedRequest(UUID connectionSessionId, DecodedClientRequest request) {}

    private static final ProtocolCapabilities SUPPORTED = ProtocolCapabilities.friendai();

    /**
     * Test seam, set only by the GameTest run: a mock player has no client to negotiate with, so without it the join
     * check below would disconnect every mock the moment it is placed and its messages would go nowhere. The player
     * is still admitted to no feature request, because the gate stays unaccepted.
     */
    static final String TOLERATE_UNNEGOTIATED_PROPERTY = "friendai.gametest.tolerateUnnegotiatedPeers";

    private final MinecraftServer server;
    private final boolean tolerateUnnegotiatedPeers = Boolean.getBoolean(TOLERATE_UNNEGOTIATED_PROPERTY);
    private final Map<UUID, Connection> connections = new HashMap<>();
    private final Map<UUID, Long> skillLastTick = new HashMap<>();
    private final Map<UUID, Long> tradeLastAction = new HashMap<>();
    private final Map<UUID, UUID> activeTradeQueries = new HashMap<>();
    private RateLimiter controlLimiter = new RateLimiter(300, System::currentTimeMillis);
    private RateLimiter skinLimiter = new RateLimiter(300, System::currentTimeMillis);
    private RateLimiter voiceLimiter = new RateLimiter(1_000, System::currentTimeMillis);

    public ServerProtocolConnections(MinecraftServer server) {
        this.server = server;
    }

    /** Opens a new pending negotiation and sends hello only when both bootstrap directions are available. */
    public void join(ServerPlayer player) {
        UUID owner = player.getUUID();
        Connection previous = connections.remove(owner);
        if (previous != null) {
            previous.gate().disconnect();
            forget(owner);
        }

        boolean canSendHello = ServerPlayNetworking.canSend(player, ProtocolHelloS2CPayload.TYPE);
        boolean canReceiveAccept =
                ServerPlayNetworking.getGlobalReceivers().contains(ProtocolAcceptC2SPayload.TYPE.id());
        ProtocolServerRequestGate gate =
                new ProtocolServerRequestGate(server.getTickCount(), SUPPORTED, canSendHello, canReceiveAccept);
        Connection connection = new Connection(player, UUID.randomUUID(), gate);
        connections.put(owner, connection);
        if (gate.state() != dev.friendai.protocol.ProtocolServerSession.State.PENDING) {
            if (!tolerateUnnegotiatedPeers) {
                disconnect(connection);
            }
            return;
        }

        gate.hello()
                .ifPresent(hello -> ServerPlayNetworking.send(
                        player, new ProtocolHelloS2CPayload(ProtocolBootstrapCodec.encodeHello(hello))));
    }

    /** Accepts one valid client hello response on the server thread. */
    public boolean accept(ServerPlayer player, byte[] encoded) {
        Connection connection = current(player).orElse(null);
        if (connection == null) return false;

        DecodeResult<dev.friendai.protocol.ProtocolAccept> decoded = ProtocolBootstrapCodec.decodeAccept(encoded);
        if (!(decoded instanceof DecodeResult.Valid<dev.friendai.protocol.ProtocolAccept> valid)) {
            disconnect(connection);
            return false;
        }

        var state = connection.gate().accept(valid.value(), server.getTickCount());
        if (state != dev.friendai.protocol.ProtocolServerSession.State.ACCEPTED) {
            disconnect(connection);
            return false;
        }
        return true;
    }

    /** Releases the matching player's connection session and rate-limit history on disconnect. */
    public void leave(ServerPlayer player) {
        Connection connection = current(player).orElse(null);
        if (connection == null) return;
        connections.remove(player.getUUID(), connection);
        connection.gate().disconnect();
        forget(player.getUUID());
    }

    /** Decodes and admits a request only for the current, accepted player connection. */
    public Optional<AdmittedRequest> admit(ServerPlayer player, byte[] encoded) {
        Connection connection = current(player).orElse(null);
        if (connection == null) return Optional.empty();
        ProtocolServerRequestGate.Admission admission = connection.gate().admit(encoded);
        return admission instanceof ProtocolServerRequestGate.Accepted accepted
                ? Optional.of(new AdmittedRequest(connection.sessionId(), accepted.request()))
                : Optional.empty();
    }

    /** Closes pending peers whose 100-tick acceptance window expired. */
    public void tick() {
        Iterator<Connection> iterator = connections.values().iterator();
        while (iterator.hasNext()) {
            Connection connection = iterator.next();
            if (connection.gate().tick(server.getTickCount())
                    == dev.friendai.protocol.ProtocolServerSession.State.TIMED_OUT) {
                iterator.remove();
                connection.gate().disconnect();
                forget(connection.player().getUUID());
                connection.player().connection.disconnect(Component.translatable("friendai.protocol.version_mismatch"));
            }
        }
    }

    public boolean isAccepted(ServerPlayer player) {
        return current(player)
                .map(connection ->
                        connection.gate().state() == dev.friendai.protocol.ProtocolServerSession.State.ACCEPTED)
                .orElse(false);
    }

    public boolean allows(ServerPlayer player, ProtocolFamily family) {
        return current(player)
                .map(connection -> connection.gate().allowsFamily(family))
                .orElse(false);
    }

    public boolean allowsAppearance(ServerPlayer player) {
        return current(player)
                .map(connection -> connection.gate().allowsAppearance())
                .orElse(false);
    }

    public Optional<UUID> sessionId(ServerPlayer player) {
        return current(player).map(Connection::sessionId);
    }

    /** Sends a framed view only to the matching accepted connection with the requested family negotiated. */
    public boolean sendView(ServerPlayer player, ProtocolFamily family, byte[] encodedFrame) {
        if (!allows(player, family) || !ServerPlayNetworking.canSend(player, ProtocolViewS2CPayload.TYPE)) return false;
        ServerPlayNetworking.send(player, new ProtocolViewS2CPayload(encodedFrame));
        return true;
    }

    public RateLimiter controlLimiter() {
        return controlLimiter;
    }

    public RateLimiter skinLimiter() {
        return skinLimiter;
    }

    public RateLimiter voiceLimiter() {
        return voiceLimiter;
    }

    /** Limits skill-menu edits to one operation per two server ticks for this owner. */
    public boolean allowSkillRequest(UUID owner) {
        long now = server.getTickCount();
        Long previous = skillLastTick.get(owner);
        if (previous != null && now - previous < 2) return false;
        skillLastTick.put(owner, now);
        return true;
    }

    /** Owns the active trade query and the per-owner menu action interval for this server connection. */
    public boolean allowTradeRequest(UUID owner, UUID queryId, boolean subscribe, boolean close) {
        long now = server.overworld().getGameTime();
        Long previous = tradeLastAction.get(owner);
        if (previous != null && now - previous < 20) return false;
        tradeLastAction.put(owner, now);
        if (subscribe) activeTradeQueries.put(owner, queryId);
        else if (close) activeTradeQueries.remove(owner);
        else if (!queryId.equals(activeTradeQueries.get(owner))) return false;
        return true;
    }

    /** Test hook for a per-server control limiter; production state remains scoped to this runtime. */
    public void setControlLimiterForTest(RateLimiter replacement) {
        controlLimiter = replacement;
    }

    /** Test hook for a per-server appearance limiter; production state remains scoped to this runtime. */
    public void setSkinLimiterForTest(RateLimiter replacement) {
        skinLimiter = replacement;
    }

    @Override
    public void close() {
        for (Connection connection : connections.values()) {
            connection.gate().disconnect();
            forget(connection.player().getUUID());
        }
        connections.clear();
        skillLastTick.clear();
        tradeLastAction.clear();
        activeTradeQueries.clear();
    }

    private Optional<Connection> current(ServerPlayer player) {
        Connection connection = connections.get(player.getUUID());
        return connection != null && connection.player() == player ? Optional.of(connection) : Optional.empty();
    }

    private void disconnect(Connection connection) {
        connections.remove(connection.player().getUUID(), connection);
        connection.gate().disconnect();
        forget(connection.player().getUUID());
        connection.player().connection.disconnect(Component.translatable("friendai.protocol.version_mismatch"));
        FriendaiMod.LOGGER.debug(
                "Disconnected a peer that could not negotiate Friendai protocol v{}", ProtocolVersion.CURRENT);
    }

    private void forget(UUID owner) {
        controlLimiter.forget(owner);
        skinLimiter.forget(owner);
        voiceLimiter.forget(owner);
        skillLastTick.remove(owner);
        tradeLastAction.remove(owner);
        activeTradeQueries.remove(owner);
    }

    private record Connection(ServerPlayer player, UUID sessionId, ProtocolServerRequestGate gate) {}
}
