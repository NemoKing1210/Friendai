package dev.friendai.transport.payload;

import dev.friendai.FriendaiMod;
import dev.friendai.protocol.ProtocolVersion;
import java.util.Objects;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Bounded Fabric wrapper for one engine-owned client request frame. */
public record ProtocolRequestC2SPayload(byte[] data) implements CustomPacketPayload {
    public static final int MAX_BYTES = ProtocolVersion.ABSOLUTE_MAX_FRAME_BYTES;
    public static final Type<ProtocolRequestC2SPayload> TYPE = new Type<>(FriendaiMod.id("protocol_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ProtocolRequestC2SPayload> CODEC = ByteBufCodecs.byteArray(
                    MAX_BYTES)
            .map(ProtocolRequestC2SPayload::new, ProtocolRequestC2SPayload::data)
            .cast();

    public ProtocolRequestC2SPayload {
        Objects.requireNonNull(data, "data");
        if (data.length > MAX_BYTES) throw new IllegalArgumentException("protocol request is too large");
        data = data.clone();
    }

    @Override
    public byte[] data() {
        return data.clone();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
