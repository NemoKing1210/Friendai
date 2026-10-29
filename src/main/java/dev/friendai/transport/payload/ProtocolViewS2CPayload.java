package dev.friendai.transport.payload;

import dev.friendai.FriendaiMod;
import dev.friendai.protocol.ProtocolVersion;
import java.util.Objects;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Bounded Fabric wrapper for one engine-owned server view frame. */
public record ProtocolViewS2CPayload(byte[] data) implements CustomPacketPayload {
    public static final int MAX_BYTES = ProtocolVersion.ABSOLUTE_MAX_FRAME_BYTES;
    public static final Type<ProtocolViewS2CPayload> TYPE = new Type<>(FriendaiMod.id("protocol_view"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ProtocolViewS2CPayload> CODEC = ByteBufCodecs.byteArray(
                    MAX_BYTES)
            .map(ProtocolViewS2CPayload::new, ProtocolViewS2CPayload::data)
            .cast();

    public ProtocolViewS2CPayload {
        Objects.requireNonNull(data, "data");
        if (data.length > MAX_BYTES) throw new IllegalArgumentException("protocol view is too large");
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
