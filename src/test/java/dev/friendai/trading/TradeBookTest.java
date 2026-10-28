package dev.friendai.trading;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TradeBookTest {
    static final UUID ACTOR = new UUID(0, 99);

    static TradeOffer offer(String item, int price, int uses, int duplicate) {
        var inputs = List.of(new TradeCost("minecraft:emerald", "{}", 20));
        var output = new TradeItem(item, "{}", 1);
        return new TradeOffer(
                "index-" + duplicate,
                TradeOffer.fingerprint(inputs, output, duplicate),
                inputs,
                List.of(new TradeCost("minecraft:emerald", "{}", price)),
                output,
                uses,
                10,
                2,
                ACTOR,
                100);
    }

    static TraderRecord trader(int id, long tick, TraderRecord.Status status, List<TradeOffer> offers) {
        return new TraderRecord(
                new UUID(0, id),
                "minecraft:villager",
                "minecraft:overworld",
                id,
                64,
                0,
                "",
                "minecraft:librarian",
                1,
                100,
                tick,
                offers.isEmpty() && status != TraderRecord.Status.VERIFIED ? -1 : 100,
                0,
                status,
                offers);
    }

    static TradeBook book() {
        var book = new TradeBook();
        book.activatePricingActor(ACTOR);
        return book;
    }

    @Test
    void sameUuidMovesAndReplacementRemainsSeparate() {
        var book = book();
        var first = trader(1, 100, TraderRecord.Status.VERIFIED, List.of(offer("minecraft:book", 2, 0, 0)));
        assertTrue(book.observe(first));
        assertTrue(book.observe(new TraderRecord(
                first.id(),
                first.entityType(),
                "minecraft:the_nether",
                9,
                70,
                3,
                "Moved",
                first.profession(),
                2,
                110,
                110,
                -1,
                0,
                TraderRecord.Status.SEEN,
                List.of())));
        book.observe(trader(2, 100, TraderRecord.Status.SEEN, List.of()));
        assertEquals(2, book.snapshot().traders().size());
        var moved = book.trader(first.id()).orElseThrow();
        assertEquals("minecraft:the_nether", moved.dimension());
        assertEquals(100, moved.firstSeen());
        assertEquals(1, moved.offers().size());
    }

    @Test
    void onlyVerifiedCompleteInspectionReplacesOffersAndOldRefCannotRedirectAfterReorder() {
        var book = book();
        var bookOffer = offer("minecraft:book", 2, 0, 0);
        var appleOffer = offer("minecraft:apple", 3, 0, 0);
        book.observe(trader(1, 100, TraderRecord.Status.VERIFIED, List.of(bookOffer, appleOffer)));
        var oldRef = book.query(new TradeQuery(TradeQuery.Direction.BUY, "minecraft:book", "", 8), 100)
                .getFirst()
                .offer()
                .ref();
        book.observe(trader(1, 110, TraderRecord.Status.BUSY, List.of(appleOffer)));
        assertEquals(2, book.snapshot().traders().getFirst().offers().size());
        book.observe(trader(1, 120, TraderRecord.Status.VERIFIED, List.of(appleOffer, bookOffer)));
        assertEquals(
                "minecraft:book",
                book.resolveOffer(new UUID(0, 1), oldRef).orElseThrow().output().itemId());
        book.observe(trader(1, 130, TraderRecord.Status.VERIFIED, List.of(appleOffer)));
        assertTrue(book.resolveOffer(new UUID(0, 1), oldRef).isEmpty());
    }

    @Test
    void indistinguishableDuplicatesRequireInspectionEvenWithDiscriminators() {
        var book = book();
        book.observe(trader(
                1,
                100,
                TraderRecord.Status.VERIFIED,
                List.of(offer("minecraft:book", 2, 0, 0), offer("minecraft:book", 3, 0, 1))));
        var matches = book.query(new TradeQuery(TradeQuery.Direction.BUY, "minecraft:book", "", 8), 100);
        assertEquals(2, matches.size());
        assertTrue(matches.stream().allMatch(TradeQuery.Match::needsInspection));
        assertTrue(book.resolveOffer(new UUID(0, 1), matches.getFirst().offer().ref())
                .isEmpty());
    }

    @Test
    void timestampsIdsAndPerTraderCapsAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> trader(1, -1, TraderRecord.Status.SEEN, List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TraderRecord(
                        null,
                        "minecraft:villager",
                        "minecraft:overworld",
                        0,
                        0,
                        0,
                        "",
                        "",
                        0,
                        0,
                        0,
                        -1,
                        0,
                        TraderRecord.Status.SEEN,
                        List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TraderRecord(
                        UUID.randomUUID(),
                        "bad",
                        "minecraft:overworld",
                        0,
                        0,
                        0,
                        "",
                        "",
                        0,
                        0,
                        0,
                        -1,
                        0,
                        TraderRecord.Status.SEEN,
                        List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TraderRecord(
                        UUID.randomUUID(),
                        "minecraft:villager",
                        "minecraft:overworld",
                        0,
                        0,
                        0,
                        "x".repeat(81),
                        "",
                        0,
                        0,
                        0,
                        -1,
                        0,
                        TraderRecord.Status.SEEN,
                        List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> trader(
                        1,
                        100,
                        TraderRecord.Status.VERIFIED,
                        java.util.Collections.nCopies(65, offer("minecraft:book", 2, 0, 0))));
        var book = book();
        book.observe(trader(1, 110, TraderRecord.Status.SEEN, List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> book.observe(trader(1, 109, TraderRecord.Status.SEEN, List.of())));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TradeOffer(
                        "ref",
                        "bad",
                        List.of(new TradeCost("minecraft:book", "{}", 1)),
                        List.of(new TradeCost("minecraft:book", "{}", 1)),
                        new TradeItem("minecraft:book", "{}", 1),
                        0,
                        1,
                        0,
                        ACTOR,
                        -1));
    }

    @Test
    void traderEvictionProtectsPinnedAndReferenceCountedActiveWork() {
        var book = book();
        for (int i = 1; i <= 256; i++) book.observe(trader(i, 100 + i, TraderRecord.Status.SEEN, List.of()));
        book.pin(new UUID(0, 1));
        book.retain(new UUID(0, 2));
        book.retain(new UUID(0, 2));
        book.release(new UUID(0, 2));
        assertTrue(book.observe(trader(257, 400, TraderRecord.Status.SEEN, List.of())));
        assertTrue(book.trader(new UUID(0, 1)).isPresent());
        assertTrue(book.trader(new UUID(0, 2)).isPresent());
        assertTrue(book.trader(new UUID(0, 3)).isEmpty());
        for (var trader : book.snapshot().traders()) book.pin(trader.id());
        long revision = book.revision();
        assertFalse(book.observe(trader(258, 400, TraderRecord.Status.SEEN, List.of())));
        assertEquals(revision, book.revision());
        book.unpin(new UUID(0, 2));
        book.release(new UUID(0, 2));
        assertTrue(book.observe(trader(258, 400, TraderRecord.Status.SEEN, List.of())));
        assertTrue(book.trader(new UUID(0, 2)).isEmpty());
    }

    @Test
    void totalOfferCapEvictsOldestAndFileRejectsOversizedCountsBeforeCopying() {
        var offers = new ArrayList<TradeOffer>();
        for (int i = 0; i < 64; i++) offers.add(offer("minecraft:book", 2, 0, i));
        var book = book();
        for (int i = 1; i <= 32; i++) book.observe(trader(i, 100 + i, TraderRecord.Status.VERIFIED, offers));
        assertTrue(book.observe(
                trader(33, 200, TraderRecord.Status.VERIFIED, List.of(offer("minecraft:apple", 2, 0, 0)))));
        assertTrue(book.trader(new UUID(0, 1)).isEmpty());
        assertEquals(
                1985,
                book.snapshot().traders().stream()
                        .mapToInt(t -> t.offers().size())
                        .sum());
        assertThrows(
                IllegalArgumentException.class,
                () -> new TradeFile(
                        1,
                        0,
                        java.util.Collections.nCopies(257, trader(1, 100, TraderRecord.Status.SEEN, List.of())),
                        List.of(),
                        Set.of()));
        var tooManyOffers = new ArrayList<TraderRecord>();
        for (int i = 1; i <= 32; i++) tooManyOffers.add(trader(i, 100, TraderRecord.Status.VERIFIED, offers));
        tooManyOffers.add(trader(33, 100, TraderRecord.Status.VERIFIED, List.of(offer("minecraft:apple", 2, 0, 0))));
        assertThrows(IllegalArgumentException.class, () -> new TradeFile(1, 0, tooManyOffers, List.of(), Set.of()));
    }

    @Test
    void snapshotRestoresPinsHistoryAndRevisionButNotActorOrActiveReferences() {
        var book = book();
        book.observe(trader(1, 100, TraderRecord.Status.VERIFIED, List.of(offer("minecraft:book", 2, 0, 0))));
        book.pin(new UUID(0, 1));
        book.retain(new UUID(0, 1));
        for (int i = 0; i < 257; i++)
            book.record(new TradeProgress(
                    new UUID(1, i),
                    TradeProgress.State.COMPLETED,
                    Map.of("minecraft:emerald", i),
                    List.of(),
                    i,
                    "done"));
        var snapshot = book.snapshot();
        assertEquals(256, snapshot.history().size());
        assertThrows(
                UnsupportedOperationException.class, () -> snapshot.traders().clear());
        var restored = new TradeBook(snapshot);
        assertEquals(snapshot.revision(), restored.revision());
        assertEquals(snapshot.pinned(), restored.snapshot().pinned());
        assertTrue(restored.query(new TradeQuery(TradeQuery.Direction.BUY, "minecraft:book", "", 8), 100)
                .getFirst()
                .quoteStale());
        long revision = restored.revision();
        restored.activatePricingActor(ACTOR);
        assertEquals(revision + 1, restored.revision());
        restored.activatePricingActor(ACTOR);
        assertEquals(revision + 1, restored.revision());
    }

    @Test
    void reusedFingerprintCannotRedirectOldReferenceToDifferentGoods() {
        var book = book();
        var original = offer("minecraft:book", 2, 0, 0);
        book.observe(trader(1, 100, TraderRecord.Status.VERIFIED, List.of(original)));
        var oldRef = book.query(new TradeQuery(TradeQuery.Direction.BUY, "minecraft:book", "", 8), 100)
                .getFirst()
                .offer()
                .ref();
        var forged = new TradeOffer(
                original.ref(),
                original.fingerprint(),
                original.baseInputs(),
                original.effectiveInputs(),
                new TradeItem("minecraft:apple", "{}", 1),
                0,
                10,
                0,
                ACTOR,
                100);
        long revision = book.revision();
        assertThrows(
                IllegalArgumentException.class,
                () -> book.observe(trader(1, 110, TraderRecord.Status.VERIFIED, List.of(forged))));
        assertEquals(revision, book.revision());
        assertEquals(
                "minecraft:book",
                book.resolveOffer(new UUID(0, 1), oldRef).orElseThrow().output().itemId());
    }

    @Test
    void protectedTotalOfferCapacityRefusesAnUpdateWithoutPartialEviction() {
        var book = book();
        var offers = new ArrayList<TradeOffer>();
        for (int i = 0; i < 64; i++) offers.add(offer("minecraft:book", 2, 0, i));
        for (int i = 1; i <= 32; i++) {
            book.observe(trader(i, 100 + i, TraderRecord.Status.VERIFIED, offers));
            book.retain(new UUID(0, i));
        }
        book.observe(trader(33, 150, TraderRecord.Status.SEEN, List.of()));
        var before = book.snapshot();
        assertFalse(book.observe(
                trader(33, 160, TraderRecord.Status.VERIFIED, List.of(offer("minecraft:apple", 2, 0, 0)))));
        assertEquals(before, book.snapshot());
    }

    @Test
    void eachSuccessfulMutationIncrementsRevisionAndOldSnapshotsStayDetached() {
        var book = book();
        long revision = book.revision();
        book.observe(trader(1, 100, TraderRecord.Status.SEEN, List.of()));
        var first = book.snapshot();
        assertEquals(revision + 1, book.revision());
        assertEquals(1, first.traders().getFirst().revision());
        book.observe(trader(1, 110, TraderRecord.Status.SEEN, List.of()));
        assertEquals(2, book.trader(new UUID(0, 1)).orElseThrow().revision());
        assertEquals(100, first.traders().getFirst().lastSeen());
        assertEquals(revision + 2, book.revision());
    }

    @Test
    void storageEvictionBridgeProtectsPinsAndAllRetainsAndAdvancesRevision() {
        var book = book();
        for (int i = 1; i <= 3; i++) book.observe(trader(i, 100, TraderRecord.Status.SEEN, List.of()));
        book.pin(new UUID(0, 1));
        book.retain(new UUID(0, 2));
        book.retain(new UUID(0, 2));
        var protectedIds = book.protectedTraders();
        assertEquals(Set.of(new UUID(0, 1), new UUID(0, 2)), protectedIds);
        assertThrows(UnsupportedOperationException.class, () -> protectedIds.clear());
        long revision = book.revision();
        assertTrue(book.evictOldestUnprotected());
        assertEquals(revision + 1, book.revision());
        assertTrue(book.trader(new UUID(0, 3)).isEmpty());
        assertFalse(book.evictOldestUnprotected());
        book.release(new UUID(0, 2));
        assertFalse(book.evictOldestUnprotected());
        book.release(new UUID(0, 2));
        assertTrue(book.evictOldestUnprotected());
        assertEquals(Set.of(new UUID(0, 1), new UUID(0, 2)), protectedIds);
        book.unpin(new UUID(0, 1));
        assertTrue(book.evictOldestUnprotected());
        assertFalse(book.evictOldestUnprotected());
    }

    @Test
    void oneObservationRejectsFingerprintCollisionBetweenDifferentGoods() {
        var book = book();
        var original = offer("minecraft:book", 2, 0, 0);
        var collision = new TradeOffer(
                "other",
                original.fingerprint(),
                original.baseInputs(),
                original.effectiveInputs(),
                new TradeItem("minecraft:apple", "{}", 1),
                0,
                10,
                0,
                ACTOR,
                100);
        assertThrows(
                IllegalArgumentException.class,
                () -> book.observe(trader(1, 100, TraderRecord.Status.VERIFIED, List.of(original, collision))));
        assertTrue(book.snapshot().traders().isEmpty());
    }

    @Test
    void removedFingerprintReappearingForDifferentGoodsCannotReuseOldRef() {
        var book = book();
        var original = offer("minecraft:book", 2, 0, 0);
        book.observe(trader(1, 100, TraderRecord.Status.VERIFIED, List.of(original)));
        var oldRef = book.query(new TradeQuery(TradeQuery.Direction.BUY, "minecraft:book", "", 8), 100)
                .getFirst()
                .offer()
                .ref();
        book.observe(trader(1, 110, TraderRecord.Status.VERIFIED, List.of()));
        var changed = new TradeOffer(
                original.ref(),
                original.fingerprint(),
                original.baseInputs(),
                original.effectiveInputs(),
                new TradeItem("minecraft:apple", "{}", 1),
                0,
                10,
                0,
                ACTOR,
                100);
        book.observe(trader(1, 120, TraderRecord.Status.VERIFIED, List.of(changed)));
        assertTrue(book.resolveOffer(new UUID(0, 1), oldRef).isEmpty());
    }

    @Test
    void revisionOverflowNeverPartiallyMutatesState() {
        var trader = trader(1, 100, TraderRecord.Status.VERIFIED, List.of(offer("minecraft:book", 2, 0, 0)));
        var book = new TradeBook(new TradeFile(1, Long.MAX_VALUE, List.of(trader), List.of(), Set.of()));
        var before = book.snapshot();
        assertThrows(
                ArithmeticException.class, () -> book.observe(trader(2, 100, TraderRecord.Status.SEEN, List.of())));
        assertEquals(before, book.snapshot());
        assertThrows(ArithmeticException.class, () -> book.pin(trader.id()));
        assertEquals(before, book.snapshot());
        assertThrows(
                ArithmeticException.class,
                () -> book.record(new TradeProgress(
                        UUID.randomUUID(), TradeProgress.State.COMPLETED, Map.of(), List.of(), 0, "")));
        assertEquals(before, book.snapshot());
        assertThrows(ArithmeticException.class, () -> book.activatePricingActor(ACTOR));
        assertTrue(book.query(new TradeQuery(TradeQuery.Direction.BUY, "minecraft:book", "", 8), 100)
                .getFirst()
                .quoteStale());
        var pinned = new TradeBook(new TradeFile(1, Long.MAX_VALUE, List.of(trader), List.of(), Set.of(trader.id())));
        var pinnedBefore = pinned.snapshot();
        assertThrows(ArithmeticException.class, () -> pinned.unpin(trader.id()));
        assertEquals(pinnedBefore, pinned.snapshot());
    }

    @Test
    void malformedUnicodeCannotRedirectRemovedQuestionMarkOfferReference() {
        var book = book();
        var inputs = List.of(new TradeCost("minecraft:emerald", "{}", 1));
        var question = new TradeItem("minecraft:book", "{\"value\":\"?\"}", 1);
        var offer = new TradeOffer(
                "question",
                TradeOffer.fingerprint(inputs, question, 0),
                inputs,
                inputs,
                question,
                0,
                10,
                0,
                ACTOR,
                100);
        book.observe(trader(1, 100, TraderRecord.Status.VERIFIED, List.of(offer)));
        String oldRef =
                book.trader(new UUID(0, 1)).orElseThrow().offers().getFirst().ref();
        book.observe(trader(1, 110, TraderRecord.Status.VERIFIED, List.of()));
        long revision = book.revision();
        assertThrows(IllegalArgumentException.class, () -> {
            var malformed = new TradeItem("minecraft:book", "{\"value\":\"\\uD800\"}", 1);
            var collision = new TradeOffer(
                    "malformed",
                    TradeOffer.fingerprint(inputs, malformed, 0),
                    inputs,
                    inputs,
                    malformed,
                    0,
                    10,
                    0,
                    ACTOR,
                    100);
            book.observe(trader(1, 120, TraderRecord.Status.VERIFIED, List.of(collision)));
        });
        assertEquals(revision, book.revision());
        assertTrue(book.resolveOffer(new UUID(0, 1), oldRef).isEmpty());
        assertTrue(book.trader(new UUID(0, 1)).orElseThrow().offers().isEmpty());
    }
}
