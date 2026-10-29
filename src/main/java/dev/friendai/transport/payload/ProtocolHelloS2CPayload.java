package dev.friendai.transport.payload;

import dev.friendai.FriendaiMod;
import dev.friendai.protocol.ProtocolBootstrapCodec;
import java.util.Objects;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Bounded server-to-client bootstrap wrapper. */
public record ProtocolHelloS2CPayload(byte[] data) implements CustomPacketPayload {
    public static final int MAX_BYTES = ProtocolBootstrapCodec.MAX_BYTES;
    public static final Type<ProtocolHelloS2CPayload> TYPE = new Type<>(FriendaiMod.id("protocol_hello"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ProtocolHelloS2CPayload> CODEC = ByteBufCodecs.byteArray(
                    MAX_BYTES)
            .map(ProtocolHelloS2CPayload::new, ProtocolHelloS2CPayload::data)
            .cast();

    public ProtocolHelloS2CPayload {
        Objects.requireNonNull(data, "data");
        if (data.length > MAX_BYTES) throw new IllegalArgumentException("protocol hello is too large");
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
