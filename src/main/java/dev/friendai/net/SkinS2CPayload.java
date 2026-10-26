package dev.friendai.net;

import dev.friendai.FriendaiMod;
import dev.friendai.skin.SkinHash;
import dev.friendai.skin.SkinImage;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** The image for a skin hash, answering a {@link SkinRequestC2SPayload}. */
public record SkinS2CPayload(String hash, boolean slim, byte[] png) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SkinS2CPayload> TYPE =
            new CustomPacketPayload.Type<>(FriendaiMod.id("skin"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SkinS2CPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(SkinHash.LENGTH * 4), SkinS2CPayload::hash,
            ByteBufCodecs.BOOL, SkinS2CPayload::slim,
            ByteBufCodecs.byteArray(SkinImage.MAX_CANONICAL), SkinS2CPayload::png,
            SkinS2CPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
