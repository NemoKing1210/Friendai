package dev.friendai.features.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TradePromptSummaryTest {
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000010");

    @Test
    void summaryIsRelevantBoundedAndLabelsStaleQuotes() {
        TradeBook book = new TradeBook();
        for (int i = 0; i < 10; i++) book.observe(trader(i, 100));

        String summary = TradePromptSummary.render(book, ACTOR, "Should I buy a diamond sword with sharpness?", 5_000);

        assertEquals(TradePromptSummary.MAX_OFFERS, summary.lines().count());
        assertTrue(summary.contains("minecraft:sharpness"), summary);
        assertTrue(summary.contains("quote=stale"), summary);
        assertTrue(TradePromptSummary.render(book, ACTOR, "Should I buy some bread?", 5_000)
                .isEmpty());
    }

    @Test
    void genericTradingQuestionSummarizesAtMostEightKnownOffers() {
        TradeBook book = new TradeBook();
        for (int i = 0; i < 10; i++) book.observe(trader(i, 5_000));

        String summary = TradePromptSummary.render(book, ACTOR, "What trades does this villager have?", 5_100);

        assertEquals(TradePromptSummary.MAX_OFFERS, summary.lines().count());
        assertTrue(summary.contains("quote=fresh, live check required"), summary);
    }

    private static TraderRecord trader(int index, long tick) {
        UUID id = UUID.nameUUIDFromBytes(("trader-" + index).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var input = new TradeCost("minecraft:emerald", "{}", 4);
        var output =
                new TradeItem("minecraft:diamond_sword", "{\"minecraft:enchantments\":{\"minecraft:sharpness\":5}}", 1);
        String fingerprint = TradeOffer.fingerprint(List.of(input), output, index);
        var offer =
                new TradeOffer("temporary", fingerprint, List.of(input), List.of(input), output, 0, 12, 0, ACTOR, tick);
        return new TraderRecord(
                id,
                "minecraft:villager",
                "minecraft:overworld",
                index,
                64,
                index,
                "",
                "minecraft:weaponsmith",
                2,
                tick,
                tick,
                tick,
                0,
                TraderRecord.Status.VERIFIED,
                List.of(offer));
    }
}
