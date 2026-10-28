package dev.friendai.net;

import dev.friendai.FriendaiMod;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** A companion reply as text, for the client to turn into speech. The chat message travels separately. */
public record CompanionSpeechS2CPayload(
        String companion, String text, UUID addressee, UUID companionId, int audibleRange)
        implements CustomPacketPayload {
    public static final int MAX_NAME = 64;
    public static final int MAX_TEXT = 2000;
    public static final CustomPacketPayload.Type<CompanionSpeechS2CPayload> TYPE =
            new CustomPacketPayload.Type<>(FriendaiMod.id("companion_speech"));
    public static final StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, CompanionSpeechS2CPayload> CODEC =
            StreamCodec.composite(
                            ByteBufCodecs.stringUtf8(MAX_NAME),
                            CompanionSpeechS2CPayload::companion,
                            ByteBufCodecs.stringUtf8(MAX_TEXT),
                            CompanionSpeechS2CPayload::text,
                            UUIDUtil.STREAM_CODEC,
                            CompanionSpeechS2CPayload::addressee,
                            UUIDUtil.STREAM_CODEC,
                            CompanionSpeechS2CPayload::companionId,
                            ByteBufCodecs.VAR_INT,
                            CompanionSpeechS2CPayload::audibleRange,
                            CompanionSpeechS2CPayload::new)
                    .cast();

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
