package dev.friendai.features.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeliveredLedgerTest {
    private static final String AXE = "minecraft:stone_axe";
    private static final String PICK = "minecraft:stone_pickaxe";

    private static boolean alive(UUID ignored) {
        return true;
    }

    private static boolean gone(UUID ignored) {
        return false;
    }

    @Test
    void countsAnEntryUpToTheWindowAndNotAfter() {
        DeliveredLedger ledger =
                DeliveredLedger.EMPTY.record(new DeliveredLedger.Entry(AXE, 1, 400, UUID.randomUUID()));
        assertEquals(1, ledger.count(AXE, 1000, DeliveredLedgerTest::alive));
        assertEquals(0, ledger.count(AXE, 1001, DeliveredLedgerTest::alive));
    }

    @Test
    void keepsOnlyTheNewestEntries() {
        DeliveredLedger ledger = DeliveredLedger.EMPTY;
        for (int i = 0; i < DeliveredLedger.MAX_ENTRIES; i++) {
            ledger = ledger.record(new DeliveredLedger.Entry(AXE, 1, i, UUID.randomUUID()));
        }
        assertEquals(DeliveredLedger.MAX_ENTRIES, ledger.entries().size());
        ledger = ledger.record(new DeliveredLedger.Entry(PICK, 1, 999, UUID.randomUUID()));
        assertEquals(DeliveredLedger.MAX_ENTRIES, ledger.entries().size());
        assertEquals(PICK, ledger.entries().get(ledger.entries().size() - 1).itemId(), "newest last");
        assertEquals(0, ledger.count(AXE, 0, DeliveredLedgerTest::alive), "the evicted oldest no longer counts");
    }

    @Test
    void sumsOneItemAndIgnoresOthers() {
        DeliveredLedger ledger = DeliveredLedger.EMPTY
                .record(new DeliveredLedger.Entry(AXE, 2, 100, UUID.randomUUID()))
                .record(new DeliveredLedger.Entry(AXE, 3, 110, UUID.randomUUID()))
                .record(new DeliveredLedger.Entry(PICK, 7, 120, UUID.randomUUID()));
        assertEquals(5, ledger.count(AXE, 200, DeliveredLedgerTest::alive));
        assertEquals(7, ledger.count(PICK, 200, DeliveredLedgerTest::alive));
        assertEquals(0, ledger.count("minecraft:diamond", 200, DeliveredLedgerTest::alive));
    }

    @Test
    void aDropThatIsNoLongerInTheWorldDoesNotCount() {
        DeliveredLedger ledger =
                DeliveredLedger.EMPTY.record(new DeliveredLedger.Entry(AXE, 1, 100, UUID.randomUUID()));
        assertEquals(1, ledger.count(AXE, 200, DeliveredLedgerTest::alive));
        assertEquals(0, ledger.count(AXE, 200, DeliveredLedgerTest::gone));
    }

    @Test
    void rejectsAnEntryThatCarriesNothing() {
        DeliveredLedger ledger = DeliveredLedger.EMPTY
                .record(new DeliveredLedger.Entry("", 1, 100, UUID.randomUUID()))
                .record(new DeliveredLedger.Entry(AXE, 0, 100, UUID.randomUUID()))
                .record(new DeliveredLedger.Entry(AXE, -2, 100, UUID.randomUUID()));
        assertEquals(DeliveredLedger.EMPTY, ledger, "a blank id or a non-positive count adds nothing");
    }

    @Test
    void theEmptyLedgerCountsNothing() {
        assertTrue(DeliveredLedger.EMPTY.isEmpty());
        assertEquals(0, DeliveredLedger.EMPTY.count(AXE, 0, DeliveredLedgerTest::alive));
        assertFalse(DeliveredLedger.EMPTY
                .record(new DeliveredLedger.Entry(AXE, 1, 0, UUID.randomUUID()))
                .isEmpty());
    }
}
