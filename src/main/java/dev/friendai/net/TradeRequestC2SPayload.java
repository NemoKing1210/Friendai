package dev.friendai.net;

import dev.friendai.FriendaiMod;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Dedicated owner-only trading UI request; it deliberately carries no owner identity. */
public record TradeRequestC2SPayload(
        Action action, String queryId, String itemId, String components, int direction, int page, String traderId)
        implements CustomPacketPayload {
    public enum Action {
        SUBSCRIBE,
        QUERY,
        CLOSE,
        INSPECT,
        REFRESH,
        PREVIEW,
        SUBMIT,
        WAIT,
        FORGET,
        BARTER
    }

    public static final CustomPacketPayload.Type<TradeRequestC2SPayload> TYPE =
            new CustomPacketPayload.Type<>(FriendaiMod.id("trade_request"));

    public TradeRequestC2SPayload {
        if (action == null
                || queryId == null
                || queryId.length() > 64
                || itemId == null
                || itemId.length() > 128
                || components == null
                || components.length() > 8192
                || direction < 0
                || direction > 1
                || page < 0
                || page > 255
                || traderId == null
                || traderId.length() > 64) throw new IllegalArgumentException("invalid trade request bounds");
    }

    public static final StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, TradeRequestC2SPayload> CODEC =
            StreamCodec.composite(
                            ByteBufCodecs.VAR_INT,
                            (TradeRequestC2SPayload p) -> p.action().ordinal(),
                            ByteBufCodecs.stringUtf8(64),
                            TradeRequestC2SPayload::queryId,
                            ByteBufCodecs.stringUtf8(128),
                            TradeRequestC2SPayload::itemId,
                            ByteBufCodecs.stringUtf8(8192),
                            TradeRequestC2SPayload::components,
                            ByteBufCodecs.VAR_INT,
                            TradeRequestC2SPayload::direction,
                            ByteBufCodecs.VAR_INT,
                            TradeRequestC2SPayload::page,
                            ByteBufCodecs.stringUtf8(64),
                            TradeRequestC2SPayload::traderId,
                            (action, queryId, itemId, components, direction, page, traderId) -> {
                                Action[] values = Action.values();
                                return new TradeRequestC2SPayload(
                                        action >= 0 && action < values.length ? values[action] : Action.CLOSE,
                                        queryId,
                                        itemId,
                                        components,
                                        direction,
                                        page,
                                        traderId);
                            })
                    .cast();

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
