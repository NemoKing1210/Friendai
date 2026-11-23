package dev.friendai.features.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeliveredLedgerEvidenceTest {
    private static final String AXE = "minecraft:stone_axe";

    @Test
    void aHandOverCountsEvenAfterTheOwnerPicksTheDropUp() {
        UUID drop = UUID.randomUUID();
        DeliveredLedger ledger = DeliveredLedger.EMPTY.record(new DeliveredLedger.Entry(AXE, 1, 100, drop, 0));

        assertEquals(1, ledger.handedOver(AXE, 90, 120));
        assertEquals(0, ledger.handedOver(AXE, 101, 120), "a hand-over from before the order proves nothing");
    }

    @Test
    void pickedUpIsCappedByWhatTheOwnerGainedOverTheirStockAtHandOver() {
        UUID first = UUID.randomUUID();
        DeliveredLedger ledger = DeliveredLedger.EMPTY
                .record(new DeliveredLedger.Entry(AXE, 2, 100, first, 1))
                .record(new DeliveredLedger.Entry(AXE, 2, 110, UUID.randomUUID(), 3));

        // The owner held 1 before the first hand-over and holds 4 now: 3 of the 4 delivered were picked up.
        assertEquals(3, ledger.pickedUp(AXE, 90, 120, 4));
        // Holding only their original stock proves no pickup at all.
        assertEquals(0, ledger.pickedUp(AXE, 90, 120, 1));
    }

    @Test
    void differentItemsAndOlderHandOversStayDistinct() {
        UUID drop = UUID.randomUUID();
        DeliveredLedger ledger = DeliveredLedger.EMPTY
                .record(new DeliveredLedger.Entry(AXE, 1, 50, drop, 0))
                .record(new DeliveredLedger.Entry("minecraft:bread", 3, 100, UUID.randomUUID(), 0));

        assertEquals(0, ledger.handedOver(AXE, 60, 120));
        assertEquals(3, ledger.handedOver("minecraft:bread", 60, 120));
        assertEquals(0, ledger.handedOver("minecraft:oak_log", 60, 120));
    }

    @Test
    void anEntryWrittenWithoutAnOwnerSnapshotReadsConservatively() {
        UUID drop = UUID.randomUUID();
        DeliveredLedger ledger = DeliveredLedger.EMPTY.record(new DeliveredLedger.Entry(AXE, 1, 100, drop));

        assertEquals(0, ledger.pickedUp(AXE, 90, 120, 0));
        assertEquals(1, ledger.pickedUp(AXE, 90, 120, 1));
        assertEquals(1, ledger.handedOver(AXE, 90, 120));
    }

    @Test
    void entriesOutsideTheWindowAreIgnored() {
        DeliveredLedger ledger =
                DeliveredLedger.EMPTY.record(new DeliveredLedger.Entry(AXE, 1, 100, UUID.randomUUID(), 0));

        assertEquals(0, ledger.handedOver(AXE, 100, 99), "a future tick is not evidence yet");
        assertEquals(0, ledger.handedOver(AXE, 101, 120));
        assertEquals(1, ledger.handedOver(AXE, 100, 100));
        assertEquals(0, ledger.count(AXE, 1_000, Set.of()::contains));
    }
}
