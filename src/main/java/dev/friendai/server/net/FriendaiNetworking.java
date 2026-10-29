package dev.friendai.server.net;

import dev.friendai.server.runtime.ServerRuntimeRegistry;
import dev.friendai.transport.payload.ProtocolAcceptC2SPayload;
import dev.friendai.transport.payload.ProtocolHelloS2CPayload;
import dev.friendai.transport.payload.ProtocolRequestC2SPayload;
import dev.friendai.transport.payload.ProtocolViewS2CPayload;
import dev.friendai.transport.payload.SkinRequestC2SPayload;
import dev.friendai.transport.payload.SkinS2CPayload;
import dev.friendai.transport.payload.SkinUploadC2SPayload;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

/** Registers the single typed protocol frame and the handshake-gated appearance payloads. */
public final class FriendaiNetworking {
    private FriendaiNetworking() {}

    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(ProtocolAcceptC2SPayload.TYPE, ProtocolAcceptC2SPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ProtocolRequestC2SPayload.TYPE, ProtocolRequestC2SPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ProtocolHelloS2CPayload.TYPE, ProtocolHelloS2CPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ProtocolViewS2CPayload.TYPE, ProtocolViewS2CPayload.CODEC);

        PayloadTypeRegistry.serverboundPlay().register(SkinUploadC2SPayload.TYPE, SkinUploadC2SPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SkinRequestC2SPayload.TYPE, SkinRequestC2SPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(SkinS2CPayload.TYPE, SkinS2CPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(
                ProtocolAcceptC2SPayload.TYPE,
                (payload, context) -> context.server()
                        .execute(() -> ServerRuntimeRegistry.getOrCreate(context.server())
                                .protocolConnections()
                                .accept(context.player(), payload.data())));
        ServerPlayNetworking.registerGlobalReceiver(
                ProtocolRequestC2SPayload.TYPE,
                (payload, context) -> context.server()
                        .execute(() -> ServerRuntimeRegistry.getOrCreate(context.server())
                                .protocolConnections()
                                .admit(context.player(), payload.data())
                                .ifPresent(request -> ProtocolRequestDispatcher.dispatch(context.player(), request))));
        ServerPlayNetworking.registerGlobalReceiver(
                SkinUploadC2SPayload.TYPE,
                (payload, context) -> context.server().execute(() -> {
                    var runtime = ServerRuntimeRegistry.getOrCreate(context.server());
                    then(
                            runtime.protocolConnections().allowsAppearance(context.player()),
                            () -> SkinHandler.upload(runtime, context.player(), payload));
                }));
        ServerPlayNetworking.registerGlobalReceiver(
                SkinRequestC2SPayload.TYPE,
                (payload, context) -> context.server().execute(() -> {
                    var runtime = ServerRuntimeRegistry.getOrCreate(context.server());
                    then(
                            runtime.protocolConnections().allowsAppearance(context.player()),
                            () -> SkinHandler.request(runtime, context.player(), payload));
                }));
    }

    private static void then(boolean allowed, Runnable action) {
        if (allowed) action.run();
    }
}
