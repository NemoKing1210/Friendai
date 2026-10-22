package dev.friedai.net;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public final class FriedaiNetworking {
    private FriedaiNetworking() {}

    /** Registers packets on both sides and the server-side receivers. */
    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(VoiceTranscriptC2SPayload.TYPE, VoiceTranscriptC2SPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(
                VoiceTranscriptC2SPayload.TYPE, (payload, context) -> VoiceTranscriptHandler.handle(context.player(), payload.text()));
    }
}
