package dev.friendai.transport.payload;

import dev.friendai.FriendaiMod;
import dev.friendai.features.appearance.SkinImage;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** The owner's new companion skin; an empty {@code png} resets it to the default. */
public record SkinUploadC2SPayload(boolean slim, byte[] png) implements CustomPacketPayload {
    public static final Type<SkinUploadC2SPayload> TYPE = new Type<>(FriendaiMod.id("skin_upload"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SkinUploadC2SPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL,
            SkinUploadC2SPayload::slim,
            ByteBufCodecs.byteArray(SkinImage.MAX_CANONICAL),
            SkinUploadC2SPayload::png,
            SkinUploadC2SPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
