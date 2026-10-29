package dev.friendai.transport.payload;

import dev.friendai.FriendaiMod;
import dev.friendai.protocol.ProtocolBootstrapCodec;
import java.util.Objects;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Bounded client-to-server bootstrap wrapper. */
public record ProtocolAcceptC2SPayload(byte[] data) implements CustomPacketPayload {
    public static final int MAX_BYTES = ProtocolBootstrapCodec.MAX_BYTES;
    public static final Type<ProtocolAcceptC2SPayload> TYPE = new Type<>(FriendaiMod.id("protocol_accept"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ProtocolAcceptC2SPayload> CODEC = ByteBufCodecs.byteArray(
                    MAX_BYTES)
            .map(ProtocolAcceptC2SPayload::new, ProtocolAcceptC2SPayload::data)
            .cast();

    public ProtocolAcceptC2SPayload {
        Objects.requireNonNull(data, "data");
        if (data.length > MAX_BYTES) throw new IllegalArgumentException("protocol accept is too large");
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
