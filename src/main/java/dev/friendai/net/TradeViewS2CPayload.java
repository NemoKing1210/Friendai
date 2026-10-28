package dev.friendai.net;

import dev.friendai.FriendaiMod;
import dev.friendai.features.trading.TradeView;
import dev.friendai.features.trading.TradeViewCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Bounded private trade page. */
public record TradeViewS2CPayload(byte[] encoded) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<TradeViewS2CPayload> TYPE =
            new CustomPacketPayload.Type<>(FriendaiMod.id("trade_view"));
    public static final StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, TradeViewS2CPayload> CODEC =
            ByteBufCodecs.byteArray(TradeView.MAX_PACKET_BYTES)
                    .map(TradeViewS2CPayload::new, TradeViewS2CPayload::encoded)
                    .cast();

    public TradeViewS2CPayload {
        if (encoded == null || encoded.length == 0 || encoded.length > TradeView.MAX_PACKET_BYTES)
            throw new IllegalArgumentException("invalid trade view packet size");
        encoded = encoded.clone();
    }

    public TradeViewS2CPayload(TradeView view) {
        this(TradeViewCodec.encode(view));
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
