package dev.friendai.net;

import dev.friendai.FriendaiMod;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** The memory tab's view as raw codec bytes; a bad payload is rejected by the client receiver. */
public record MemoryViewS2CPayload(byte[] data) implements CustomPacketPayload {
    /** The largest view that fits on the wire; the stream trims facts rather than overstep it. */
    public static final int MAX_BYTES = 1 << 20;

    public static final CustomPacketPayload.Type<MemoryViewS2CPayload> TYPE =
            new CustomPacketPayload.Type<>(FriendaiMod.id("memory_view"));

    public static final StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, MemoryViewS2CPayload> CODEC =
            ByteBufCodecs.byteArray(MAX_BYTES)
                    .map(MemoryViewS2CPayload::new, MemoryViewS2CPayload::data)
                    .cast();

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
