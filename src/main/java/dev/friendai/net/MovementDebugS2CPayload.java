package dev.friendai.net;

import dev.friendai.FriendaiMod;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * The movement debug overlay as raw codec bytes; an empty array means the overlay has nothing to show. A bad payload
 * is rejected by the client receiver.
 */
public record MovementDebugS2CPayload(byte[] data) implements CustomPacketPayload {
    public static final int MAX_BYTES = 1 << 16;

    public static final CustomPacketPayload.Type<MovementDebugS2CPayload> TYPE =
            new CustomPacketPayload.Type<>(FriendaiMod.id("movement_debug"));

    public static final StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, MovementDebugS2CPayload> CODEC =
            ByteBufCodecs.byteArray(MAX_BYTES)
                    .map(MovementDebugS2CPayload::new, MovementDebugS2CPayload::data)
                    .cast();

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
