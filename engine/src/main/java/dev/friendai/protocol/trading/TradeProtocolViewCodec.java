package dev.friendai.protocol.trading;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.friendai.features.trading.TradeView;
import dev.friendai.features.trading.TraderRecord;
import dev.friendai.protocol.DecodeResult;
import dev.friendai.protocol.ProtocolEnumAdapter;
import dev.friendai.protocol.ProtocolFamily;
import dev.friendai.protocol.ProtocolJsonViewCodec;
import dev.friendai.protocol.ProtocolVersion;
import dev.friendai.protocol.ProtocolView;
import dev.friendai.protocol.ProtocolViewCodec;
import java.lang.reflect.Type;
import java.util.Map;

/** Bounded framed trade view codec with explicit trader-status IDs. */
public final class TradeProtocolViewCodec {
    public static final int MAX_FRAME_BYTES = 65_536;
    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(
                    TraderRecord.Status.class,
                    new ProtocolEnumAdapter<>(
                                    Map.of(
                                            TraderRecord.Status.SEEN,
                                            1,
                                            TraderRecord.Status.VERIFIED,
                                            2,
                                            TraderRecord.Status.BUSY,
                                            3,
                                            TraderRecord.Status.NOT_FOUND,
                                            4,
                                            TraderRecord.Status.UNREACHABLE,
                                            5,
                                            TraderRecord.Status.DEAD_CONFIRMED,
                                            6,
                                            TraderRecord.Status.EXPIRED,
                                            7),
                                    id -> switch (id) {
                                        case 1 -> TraderRecord.Status.SEEN;
                                        case 2 -> TraderRecord.Status.VERIFIED;
                                        case 3 -> TraderRecord.Status.BUSY;
                                        case 4 -> TraderRecord.Status.NOT_FOUND;
                                        case 5 -> TraderRecord.Status.UNREACHABLE;
                                        case 6 -> TraderRecord.Status.DEAD_CONFIRMED;
                                        case 7 -> TraderRecord.Status.EXPIRED;
                                        default -> null;
                                    })
                            .nullSafe())
            .create();
    private static final Type TYPE = TradeView.class;

    private TradeProtocolViewCodec() {}

    public static byte[] encode(TradeView view) {
        return encode(view, 0);
    }

    public static byte[] encode(TradeView view, long requestId) {
        byte[] body =
                ProtocolJsonViewCodec.encodeBody(view, TYPE, GSON, MAX_FRAME_BYTES - ProtocolVersion.HEADER_BYTES);
        return ProtocolViewCodec.encode(ProtocolFamily.TRADING, 128, requestId, body);
    }

    public static DecodeResult<ProtocolView<TradeView>> decode(byte[] encoded) {
        return ProtocolJsonViewCodec.decode(encoded, ProtocolFamily.TRADING, 128, TYPE, GSON, view -> {
            if (view.traders().size() > TradeView.MAX_TRADERS || view.offers().size() > TradeView.MAX_OFFERS)
                throw new IllegalArgumentException("trade view list limit exceeded");
        });
    }
}
