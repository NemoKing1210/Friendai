package dev.friendai.server.net;

import dev.friendai.protocol.DecodedClientRequest;
import dev.friendai.server.runtime.ServerRuntimeRegistry;
import net.minecraft.server.level.ServerPlayer;

/** Resolves the admitted network request to its authenticated connection context. */
public final class ProtocolRequestDispatcher {
    private ProtocolRequestDispatcher() {}

    public static void dispatch(ServerPlayer player, ServerProtocolConnections.AdmittedRequest admitted) {
        DecodedClientRequest request = admitted.request();
        ServerRequestDispatcher.handle(
                new AuthenticatedRequestContext(
                        player,
                        admitted.connectionSessionId(),
                        request.requestId(),
                        ServerRuntimeRegistry.getOrCreate(player.level().getServer())),
                request.request());
    }
}
