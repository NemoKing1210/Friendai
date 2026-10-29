package dev.friendai.transport.payload;

import dev.friendai.FriendaiMod;
import dev.friendai.features.appearance.SkinHash;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** A client asking for the image behind a skin hash it saw on a companion. */
public record SkinRequestC2SPayload(String hash) implements CustomPacketPayload {
    public static final Type<SkinRequestC2SPayload> TYPE = new Type<>(FriendaiMod.id("skin_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SkinRequestC2SPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(SkinHash.LENGTH * 4), SkinRequestC2SPayload::hash, SkinRequestC2SPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
