package dev.friendai.transport.payload;

import dev.friendai.FriendaiMod;
import dev.friendai.features.appearance.SkinHash;
import dev.friendai.features.appearance.SkinImage;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** The image for a skin hash, answering a {@link SkinRequestC2SPayload}. */
public record SkinS2CPayload(String hash, boolean slim, byte[] png) implements CustomPacketPayload {
    public static final Type<SkinS2CPayload> TYPE = new Type<>(FriendaiMod.id("skin"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SkinS2CPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(SkinHash.LENGTH * 4),
            SkinS2CPayload::hash,
            ByteBufCodecs.BOOL,
            SkinS2CPayload::slim,
            ByteBufCodecs.byteArray(SkinImage.MAX_CANONICAL),
            SkinS2CPayload::png,
            SkinS2CPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
