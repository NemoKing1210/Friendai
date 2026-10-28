package dev.friendai.net;

import dev.friendai.FriendaiMod;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** What the player said by voice, already transcribed on their client. */
public record VoiceTranscriptC2SPayload(String text) implements CustomPacketPayload {
    public static final int MAX_CHARS = 500;
    public static final CustomPacketPayload.Type<VoiceTranscriptC2SPayload> TYPE =
            new CustomPacketPayload.Type<>(FriendaiMod.id("voice_transcript"));
    public static final StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, VoiceTranscriptC2SPayload> CODEC =
            ByteBufCodecs.stringUtf8(MAX_CHARS)
                    .map(VoiceTranscriptC2SPayload::new, VoiceTranscriptC2SPayload::text)
                    .cast();

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
