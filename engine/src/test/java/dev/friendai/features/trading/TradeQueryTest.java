package dev.friendai.features.trading;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TradeQueryTest {
    @Test
    void quoteAndPositionBoundariesActorAndBackwardClockAreExplicit() {
        var book = TradeBookTest.book();
        book.observe(TradeBookTest.trader(
                1, 100, TraderRecord.Status.VERIFIED, List.of(TradeBookTest.offer("minecraft:book", 2, 0, 0))));
        var query = new TradeQuery(TradeQuery.Direction.BUY, "minecraft:book", "", 8);
        assertFalse(book.query(query, 1299).getFirst().quoteStale());
        assertTrue(book.query(query, 1300).getFirst().quoteStale());
        assertFalse(book.query(query, 12099).getFirst().positionStale());
        assertTrue(book.query(query, 12100).getFirst().positionStale());
        assertTrue(book.query(query, 99).getFirst().quoteStale());
        assertTrue(book.query(query, -1).getFirst().positionStale());
        book.activatePricingActor(UUID.randomUUID());
        assertTrue(book.query(query, 100).getFirst().quoteStale());
        assertEquals(1, book.query(query, 100).size());
        assertTrue(TradeText.describe(book.query(query, 1300).getFirst()).contains("stale"));
        assertTrue(TradeText.describe(book.query(query, 1300).getFirst()).contains("last seen"));
    }

    @Test
    void buysInspectOutputsSellsInspectBothEffectiveInputsAndSelectedComponents() {
        var book = TradeBookTest.book();
        var inputs = List.of(
                new TradeCost("minecraft:emerald", "{}", 20), new TradeCost("minecraft:book", "{\"custom\":1}", 1));
        var output = new TradeItem(
                "minecraft:enchanted_book", "{\"enchantments\":{\"minecraft:mending\":1},\"name\":\"Book\"}", 1);
        var offer = new TradeOffer(
                "ref",
                TradeOffer.fingerprint(inputs, output, 0),
                inputs,
                inputs,
                output,
                0,
                10,
                2,
                TradeBookTest.ACTOR,
                100);
        book.observe(TradeBookTest.trader(1, 100, TraderRecord.Status.VERIFIED, List.of(offer)));
        assertEquals(
                1,
                book.query(
                                new TradeQuery(
                                        TradeQuery.Direction.BUY,
                                        output.itemId(),
                                        "{\"enchantments\":{\"minecraft:mending\":1}}",
                                        8),
                                100)
                        .size());
        assertTrue(book.query(
                        new TradeQuery(
                                TradeQuery.Direction.BUY,
                                output.itemId(),
                                "{\"enchantments\":{\"minecraft:mending\":2}}",
                                8),
                        100)
                .isEmpty());
        assertEquals(
                1,
                book.query(new TradeQuery(TradeQuery.Direction.SELL, "minecraft:book", "{\"custom\":1}", 8), 100)
                        .size());
        assertTrue(book.query(new TradeQuery(TradeQuery.Direction.BUY, "minecraft:book", "", 8), 100)
                .isEmpty());
    }

    @Test
    void deterministicOrderLimitsAndAvailabilityLabels() {
        var book = TradeBookTest.book();
        book.observe(TradeBookTest.trader(
                2, 100, TraderRecord.Status.VERIFIED, List.of(TradeBookTest.offer("minecraft:book", 2, 10, 0))));
        book.observe(TradeBookTest.trader(
                1, 100, TraderRecord.Status.VERIFIED, List.of(TradeBookTest.offer("minecraft:book", 2, 0, 0))));
        var query = new TradeQuery(TradeQuery.Direction.BUY, "minecraft:book", "", 1);
        assertEquals(new UUID(0, 1), book.query(query, 100).getFirst().trader().id());
        assertEquals(1, book.query(query, 100).size());
        var all = book.query(new TradeQuery(TradeQuery.Direction.BUY, "minecraft:book", "", 40), 100);
        assertTrue(TradeText.describe(all.get(1)).contains("exhausted"));
        assertTrue(TradeText.describe(TradeBookTest.trader(3, 100, TraderRecord.Status.SEEN, List.of()))
                .contains("unknown"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TradeQuery(TradeQuery.Direction.BUY, "minecraft:book", "", 41));
        assertThrows(IllegalArgumentException.class, () -> new TradeQuery(null, "minecraft:book", "", 1));
    }

    @Test
    void selectedComponentNumbersMatchExactlyWithoutDoubleRounding() {
        var inputs = List.of(new TradeCost("minecraft:emerald", "{}", 2));
        var output = new TradeItem("minecraft:filled_map", "{\"custom_id\":9007199254740992}", 1);
        var offer = new TradeOffer(
                "ref",
                TradeOffer.fingerprint(inputs, output, 0),
                inputs,
                inputs,
                output,
                0,
                1,
                0,
                TradeBookTest.ACTOR,
                100);
        assertFalse(new TradeQuery(TradeQuery.Direction.BUY, output.itemId(), "{\"custom_id\":9007199254740993}", 1)
                .matches(offer));
    }
}
