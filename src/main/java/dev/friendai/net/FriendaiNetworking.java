package dev.friendai.net;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public final class FriendaiNetworking {
    private FriendaiNetworking() {}

    /** Registers packets on both sides and the server-side receivers. */
    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(VoiceTranscriptC2SPayload.TYPE, VoiceTranscriptC2SPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(CompanionRequestC2SPayload.TYPE, CompanionRequestC2SPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(CompanionSnapshotS2CPayload.TYPE, CompanionSnapshotS2CPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(PromptS2CPayload.TYPE, PromptS2CPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(MemoryViewS2CPayload.TYPE, MemoryViewS2CPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(
                VoiceTranscriptC2SPayload.TYPE,
                (payload, context) -> context.server().execute(
                        () -> VoiceTranscriptHandler.handle(context.player(), payload.text())));
        ServerPlayNetworking.registerGlobalReceiver(
                CompanionRequestC2SPayload.TYPE,
                (payload, context) -> context.server().execute(() -> CompanionRequestHandler.handle(context.player(), payload)));
    }
}
