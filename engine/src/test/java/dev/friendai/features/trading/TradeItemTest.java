package dev.friendai.features.trading;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TradeItemTest {
    @Test
    void componentsDistinguishBooksAndCanonicalizeObjectOrder() {
        var mending = new TradeItem(
                "minecraft:enchanted_book", "{\"enchantments\":{\"minecraft:mending\":1},\"custom\":true}", 1);
        var reordered = new TradeItem(
                "minecraft:enchanted_book", "{\"custom\":true,\"enchantments\":{\"minecraft:mending\":1.0}}", 1);
        assertEquals(mending, reordered);
        assertNotEquals(
                mending,
                new TradeItem("minecraft:enchanted_book", "{\"enchantments\":{\"minecraft:unbreaking\":3}}", 1));
    }

    @Test
    void rejectsInvalidIdsCountsMalformedOversizedOrNestedJson() {
        assertThrows(IllegalArgumentException.class, () -> new TradeItem("bad id", "{}", 1));
        assertThrows(IllegalArgumentException.class, () -> new TradeCost("minecraft:book", "{}", 0));
        assertThrows(IllegalArgumentException.class, () -> new TradeItem("minecraft:book", "{}", 1_000_001));
        assertThrows(IllegalArgumentException.class, () -> new TradeItem("minecraft:book", "{broken", 1));
        assertThrows(IllegalArgumentException.class, () -> new TradeItem("minecraft:book", "{\"x\":1,\"x\":2}", 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TradeItem("minecraft:book", "{\"x\":\"" + "я".repeat(4096) + "\"}", 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TradeItem("minecraft:book", "{\"x\":".repeat(17) + "0" + "}".repeat(17), 1));
        assertThrows(IllegalArgumentException.class, () -> new TradeItem("minecraft:book", "[]", 1));
    }

    @Test
    void fingerprintIncludesBasePredicateFullOutputAndDuplicateButNotDynamicPrice() {
        var base = List.of(new TradeCost("minecraft:emerald", "{}", 20));
        var output = new TradeItem("minecraft:enchanted_book", "{\"mending\":1}", 1);
        var fingerprint = TradeOffer.fingerprint(base, output, 0);
        var first = new TradeOffer("ref", fingerprint, base, base, output, 0, 10, 2, UUID.randomUUID(), 100);
        var discount = new TradeOffer(
                "ref",
                fingerprint,
                base,
                List.of(new TradeCost("minecraft:emerald", "{}", 1)),
                output,
                3,
                10,
                2,
                first.pricingActor(),
                101);
        assertEquals(first.fingerprint(), discount.fingerprint());
        assertNotEquals(fingerprint, TradeOffer.fingerprint(base, output, 1));
        assertNotEquals(
                fingerprint,
                TradeOffer.fingerprint(List.of(new TradeCost("minecraft:emerald", "{\"custom\":1}", 20)), output, 0));
        assertNotEquals(
                fingerprint, TradeOffer.fingerprint(base, new TradeItem(output.itemId(), "{\"mending\":2}", 1), 0));
        assertEquals(2, first.merchantXp());
        assertFalse(first.exhausted());
        assertTrue(new TradeOffer("ref", fingerprint, base, base, output, 10, 10, 2, first.pricingActor(), 101)
                .exhausted());
    }

    @Test
    void offerAndProgressDefensivelyCopyAndValidate() {
        var base = new java.util.ArrayList<>(List.of(new TradeCost("minecraft:emerald", "{}", 2)));
        var item = new TradeItem("minecraft:book", "{}", 1);
        var offer = new TradeOffer(
                "ref", TradeOffer.fingerprint(base, item, 0), base, base, item, 0, 1, 0, UUID.randomUUID(), 1);
        base.clear();
        assertEquals(1, offer.baseInputs().size());
        assertThrows(
                UnsupportedOperationException.class, () -> offer.baseInputs().clear());
        assertThrows(
                IllegalArgumentException.class,
                () -> new TradeOffer(
                        "ref", offer.fingerprint(), List.of(), List.of(), item, 0, 1, 0, offer.pricingActor(), 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TradeOffer(
                        "ref",
                        offer.fingerprint(),
                        offer.baseInputs(),
                        offer.effectiveInputs(),
                        item,
                        2,
                        1,
                        0,
                        offer.pricingActor(),
                        1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TradeProgress(null, TradeProgress.State.FAILED, java.util.Map.of(), List.of(), 0, ""));
    }

    @Test
    void acceptsExactByteAndNestingLimitsAndRejectsOneBeyond() {
        String exact = "{\"x\":\"" + "a".repeat(8184) + "\"}";
        assertEquals(8192, exact.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertEquals(exact, new TradeItem("minecraft:book", exact, 1).componentsJson());
        assertThrows(
                IllegalArgumentException.class, () -> new TradeCost("minecraft:book", exact.replace("aaa", "aaaa"), 1));
        String depth16 = "{\"x\":".repeat(16) + "0" + "}".repeat(16);
        assertEquals(depth16, new TradeCost("minecraft:book", depth16, 1).predicateJson());
        assertThrows(
                IllegalArgumentException.class, () -> new TradeCost("minecraft:book", "{\"x\":" + depth16 + "}", 1));
    }

    @Test
    void allValueCollectionAndArithmeticCapsRejectBeforeCopying() {
        var output = new TradeItem("minecraft:book", "{}", 1);
        var one = List.of(new TradeCost("minecraft:emerald", "{}", 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TradeOffer(
                        "ref",
                        TradeOffer.fingerprint(one, output, 0),
                        java.util.Collections.nCopies(3, one.getFirst()),
                        one,
                        output,
                        0,
                        1,
                        0,
                        UUID.randomUUID(),
                        100));
        var receipts = java.util.Collections.nCopies(2049, output);
        assertThrows(
                IllegalArgumentException.class,
                () -> new TradeProgress(
                        UUID.randomUUID(), TradeProgress.State.COMPLETED, java.util.Map.of(), receipts, 1, ""));
        var inputs = new java.util.HashMap<String, Integer>();
        for (int i = 0; i < 257; i++) inputs.put("minecraft:item_" + i, 1);
        assertThrows(
                IllegalArgumentException.class,
                () -> new TradeProgress(UUID.randomUUID(), TradeProgress.State.COMPLETED, inputs, List.of(), 1, ""));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TradeProgress(
                        UUID.randomUUID(),
                        TradeProgress.State.COMPLETED,
                        java.util.Map.of("minecraft:emerald", Integer.MAX_VALUE, "minecraft:book", 1),
                        List.of(),
                        1,
                        ""));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TradeProgress(
                        UUID.randomUUID(),
                        TradeProgress.State.COMPLETED,
                        java.util.Map.of("minecraft:emerald", -1),
                        List.of(),
                        1,
                        ""));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TradeProgress(
                        UUID.randomUUID(),
                        TradeProgress.State.FAILED,
                        java.util.Map.of(),
                        List.of(),
                        0,
                        "x".repeat(513)));
        var progress = new TradeProgress(
                UUID.randomUUID(), TradeProgress.State.COMPLETED, java.util.Map.of(), List.of(), 1, "");
        assertThrows(
                IllegalArgumentException.class,
                () -> new TradeFile(
                        TradeFile.VERSION,
                        0,
                        List.of(),
                        java.util.Collections.nCopies(257, progress),
                        java.util.Set.of(),
                        List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TradeFile(TradeFile.VERSION + 1, 0, List.of(), List.of(), java.util.Set.of(), List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TradeFile(
                        TradeFile.VERSION, 0, List.of(), List.of(), java.util.Set.of(UUID.randomUUID()), List.of()));
        assertThrows(
                IllegalArgumentException.class, () -> new TradeItem("minecraft:book", "{\"value\":100e2147483647}", 1));
    }

    @Test
    void zeroMaximumUsesIsAnExhaustedObservation() {
        var inputs = List.of(new TradeCost("minecraft:emerald", "{}", 1));
        var output = new TradeItem("minecraft:book", "{}", 1);
        assertTrue(new TradeOffer(
                        "ref",
                        TradeOffer.fingerprint(inputs, output, 0),
                        inputs,
                        inputs,
                        output,
                        0,
                        0,
                        0,
                        UUID.randomUUID(),
                        100)
                .exhausted());
    }

    @Test
    void rejectsUnpairedSurrogatesInJsonKeysAndStringValues() {
        var malformed = List.of(
                "\\uD800", "\\uDC00", "\\uD800x", "\\uDC00\\uD800", String.valueOf((char) 0xD800), String.valueOf((char)
                        0xDC00));
        for (var value : malformed) {
            String key = "{\"" + value + "\":true}";
            String string = "{\"value\":\"" + value + "\"}";
            assertThrows(IllegalArgumentException.class, () -> new TradeItem("minecraft:book", key, 1));
            assertThrows(IllegalArgumentException.class, () -> new TradeItem("minecraft:book", string, 1));
            assertThrows(IllegalArgumentException.class, () -> new TradeCost("minecraft:book", key, 1));
            assertThrows(IllegalArgumentException.class, () -> new TradeCost("minecraft:book", string, 1));
        }
    }

    @Test
    void validSupplementaryUnicodeCanonicalizesWithoutQuestionMarkReplacement() {
        String pair = new String(Character.toChars(0x1F600));
        var raw = new TradeItem("minecraft:book", "{\"" + pair + "\":\"" + pair + "\"}", 1);
        var escaped = new TradeItem("minecraft:book", "{\"\\uD83D\\uDE00\":\"\\uD83D\\uDE00\"}", 1);
        var question = new TradeItem("minecraft:book", "{\"?\":\"?\"}", 1);
        var inputs = List.of(new TradeCost("minecraft:emerald", "{}", 1));
        assertEquals(raw, escaped);
        assertNotEquals(TradeOffer.fingerprint(inputs, question, 0), TradeOffer.fingerprint(inputs, raw, 0));
    }
}
