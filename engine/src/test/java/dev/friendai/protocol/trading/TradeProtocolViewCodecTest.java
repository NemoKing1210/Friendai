package dev.friendai.protocol.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.trading.TradeView;
import dev.friendai.features.trading.TraderRecord;
import dev.friendai.protocol.DecodeResult;
import dev.friendai.protocol.ProtocolFamily;
import dev.friendai.protocol.ProtocolVersion;
import dev.friendai.protocol.ProtocolView;
import dev.friendai.protocol.ProtocolViewCodec;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class TradeProtocolViewCodecTest {
    @Test
    void framesTradeViewsAndUsesExplicitTraderStatusIds() {
        TraderRecord trader = new TraderRecord(
                UUID.randomUUID(),
                "minecraft:villager",
                "minecraft:overworld",
                1,
                64,
                2,
                "Ada",
                "minecraft:librarian",
                3,
                1,
                80,
                80,
                1,
                TraderRecord.Status.VERIFIED,
                List.of());
        TradeView view = new TradeView(UUID.randomUUID(), 4, 0, 1, List.of(trader), List.of(), 80, 1_200);

        byte[] encoded = TradeProtocolViewCodec.encode(view, 72);
        String json = new String(
                encoded,
                ProtocolVersion.HEADER_BYTES,
                encoded.length - ProtocolVersion.HEADER_BYTES,
                StandardCharsets.UTF_8);
        assertTrue(json.contains("\"status\":2"));
        DecodeResult.Valid<ProtocolView<TradeView>> decoded =
                assertInstanceOf(DecodeResult.Valid.class, TradeProtocolViewCodec.decode(encoded));
        assertEquals(72, decoded.value().requestId());
        assertEquals(view, decoded.value().value());
        byte[] invalidStatus = ProtocolViewCodec.encode(
                ProtocolFamily.TRADING,
                128,
                72,
                json.replace("\"status\":2", "\"status\":9").getBytes(StandardCharsets.UTF_8));
        assertInstanceOf(DecodeResult.Malformed.class, TradeProtocolViewCodec.decode(invalidStatus));
        byte[] fractionalStatus = ProtocolViewCodec.encode(
                ProtocolFamily.TRADING,
                128,
                72,
                json.replace("\"status\":2", "\"status\":2.0").getBytes(StandardCharsets.UTF_8));
        assertInstanceOf(DecodeResult.Malformed.class, TradeProtocolViewCodec.decode(fractionalStatus));
    }
}
