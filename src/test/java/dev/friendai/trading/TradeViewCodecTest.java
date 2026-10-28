package dev.friendai.trading;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TradeViewCodecTest {
    private static final UUID OWNER = new UUID(0, 1);
    private static final UUID TRADER = new UUID(0, 2);
    private static final TradeCost COST = new TradeCost("minecraft:emerald", "{}", 2);
    private static final TradeItem OUTPUT = new TradeItem(
            "minecraft:enchanted_book",
            "{\"minecraft:stored_enchantments\":{\"levels\":{\"minecraft:mending\":1}}}",
            1);
    private static final TradeOffer OFFER = new TradeOffer(
            "ref",
            TradeOffer.fingerprint(List.of(COST), OUTPUT, 0),
            List.of(COST),
            List.of(COST),
            OUTPUT,
            0,
            12,
            1,
            OWNER,
            80);

    @Test
    void roundTripsBoundedComponentAwareView() {
        var trader = new TraderRecord(
                TRADER,
                "minecraft:villager",
                "minecraft:overworld",
                1,
                2,
                3,
                "Ada",
                "minecraft:librarian",
                3,
                1,
                80,
                80,
                1,
                TraderRecord.Status.VERIFIED,
                List.of(OFFER));
        var view = new TradeView(UUID.randomUUID(), 4, 0, 1, List.of(trader), List.of(OFFER));
        assertEquals(view, TradeViewCodec.decode(TradeViewCodec.encode(view)).orElseThrow());
    }

    @Test
    void rejectsTruncatedOversizedAndDeeplyNestedPages() {
        var trader = new TraderRecord(
                TRADER,
                "minecraft:villager",
                "minecraft:overworld",
                1,
                2,
                3,
                "Ada",
                "minecraft:librarian",
                3,
                1,
                80,
                80,
                1,
                TraderRecord.Status.VERIFIED,
                List.of(OFFER));
        byte[] encoded =
                TradeViewCodec.encode(new TradeView(UUID.randomUUID(), 4, 0, 1, List.of(trader), List.of(OFFER)));
        assertTrue(TradeViewCodec.decode(java.util.Arrays.copyOf(encoded, encoded.length - 1))
                .isEmpty());
        assertTrue(
                TradeViewCodec.decode(new byte[TradeView.MAX_PACKET_BYTES + 1]).isEmpty());
        assertTrue(TradeViewCodec.decode(
                        ("[".repeat(100) + "]".repeat(100)).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .isEmpty());
    }
}
