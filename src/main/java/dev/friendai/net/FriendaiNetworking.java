package dev.friendai.net;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

public final class FriendaiNetworking {
    private FriendaiNetworking() {}

    /** Registers packets on both sides and the server-side receivers. */
    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(VoiceTranscriptC2SPayload.TYPE, VoiceTranscriptC2SPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(CompanionRequestC2SPayload.TYPE, CompanionRequestC2SPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(CompanionSnapshotS2CPayload.TYPE, CompanionSnapshotS2CPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(PromptS2CPayload.TYPE, PromptS2CPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(MemoryViewS2CPayload.TYPE, MemoryViewS2CPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(PlacesViewS2CPayload.TYPE, PlacesViewS2CPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(CompanionSpeechS2CPayload.TYPE, CompanionSpeechS2CPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SkinUploadC2SPayload.TYPE, SkinUploadC2SPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SkinRequestC2SPayload.TYPE, SkinRequestC2SPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(SkinS2CPayload.TYPE, SkinS2CPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(MovementDebugS2CPayload.TYPE, MovementDebugS2CPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(TradeRequestC2SPayload.TYPE, TradeRequestC2SPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(TradeViewS2CPayload.TYPE, TradeViewS2CPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(
                VoiceTranscriptC2SPayload.TYPE,
                (payload, context) -> context.server().execute(
                        () -> VoiceTranscriptHandler.handle(context.player(), payload.text())));
        ServerPlayNetworking.registerGlobalReceiver(
                CompanionRequestC2SPayload.TYPE,
                (payload, context) -> context.server().execute(() -> CompanionRequestHandler.handle(context.player(), payload)));
        ServerPlayNetworking.registerGlobalReceiver(TradeRequestC2SPayload.TYPE,
                (payload, context) -> context.server().execute(() -> TradeRequestHandler.handle(context.player(), payload)));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                TradeRequestHandler.disconnect(handler.getPlayer().getUUID()));
        ServerPlayNetworking.registerGlobalReceiver(
                SkinUploadC2SPayload.TYPE,
                (payload, context) -> context.server().execute(() -> SkinHandler.upload(context.player(), payload)));
        ServerPlayNetworking.registerGlobalReceiver(
                SkinRequestC2SPayload.TYPE,
                (payload, context) -> context.server().execute(() -> SkinHandler.request(context.player(), payload)));
    }
}
