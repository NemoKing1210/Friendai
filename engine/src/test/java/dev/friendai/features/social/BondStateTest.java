package dev.friendai.features.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BondStateTest {
    @Test
    void aSnapshotRoundTripsEveryField() {
        BondLedger ledger = new BondLedger();
        ledger.rescue(1);
        ledger.advanceDay(2);
        ledger.gift("diamond", 4, 2);
        ledger.advanceDay(3);
        ledger.sharedWork(3);

        BondState saved = ledger.snapshot();
        assertEquals(8, saved.points());
        assertEquals(3, saved.lastDay());
        assertEquals(2, saved.awardedToday());
        assertEquals(List.of("shared work", "gift: diamond", "rescue"), saved.reasons());
        assertEquals(Map.of("diamond", 4), saved.peaks());

        BondLedger restored = new BondLedger();
        restored.restore(saved);
        assertEquals(saved, restored.snapshot(), "every field comes back unchanged");
        assertEquals(ledger.score(), restored.score());
        assertEquals(ledger.reasons(), restored.reasons());
        assertEquals(ledger.tier(), restored.tier());
    }

    @Test
    void aRestoredLedgerKeepsItsPeaksAndItsDailyCap() {
        BondLedger ledger = new BondLedger();
        ledger.gift("diamond", 4, 1);
        BondLedger restored = new BondLedger();
        restored.restore(ledger.snapshot());

        assertFalse(restored.gift("diamond", 4, 1), "the peak came back with the state, so put, take, put is one deed");
        assertFalse(restored.gift("diamond", 2, 1), "a smaller inventory is not growth");
        assertTrue(restored.gift("diamond", 5, 1));
        assertEquals(6, restored.score(), "two growths of three points fit the cap exactly");
        assertFalse(restored.sharedWork(1), "the restored daily counter still refuses past the cap");
    }

    @Test
    void restoringNothingGivesAFreshLedger() {
        BondLedger ledger = new BondLedger();
        ledger.rescue(1);
        ledger.restore(null);
        assertEquals(BondState.EMPTY, ledger.snapshot());
        assertEquals(BondLedger.BASE, ledger.score());
        assertTrue(ledger.reasons().isEmpty());
    }

    @Test
    void theDailyCapRefusesWithoutRecordingAReason() {
        BondLedger ledger = new BondLedger();
        assertTrue(ledger.rescue(1));
        assertFalse(ledger.rescue(1), "five more points would break the cap of six");
        assertEquals(List.of("rescue"), ledger.reasons(), "a refused award leaves no reason behind");
        assertEquals(5, ledger.score());
    }

    @Test
    void oneClosedInventoryAwardsOnceAtItsHighestValue() {
        BondLedger ledger = new BondLedger();
        assertTrue(closeInventory(ledger, "diamond", 4, 2));
        assertFalse(closeInventory(ledger, "diamond", 4, 2), "put, take, put of the same item is one deed");
        assertFalse(closeInventory(ledger, "diamond", 3, 2), "a smaller inventory is not growth");
        assertTrue(closeInventory(ledger, "diamond", 5, 2));
        ledger.advanceDay(3);
        assertTrue(closeInventory(ledger, "emerald", 1, 3), "each kind keeps its own peak");

        assertEquals(Map.of("diamond", 5, "emerald", 1), ledger.snapshot().peaks(), "peaks only grow");
        assertEquals(3, ledger.reasons().size(), "three awards, three reasons");
    }

    @Test
    void theStateCopiesAndCapsWhatItIsGiven() {
        List<String> reasons = new ArrayList<>(List.of("a", "b", "c", "d"));
        Map<String, Integer> peaks = new HashMap<>(Map.of("diamond", 4));
        BondState state = new BondState(5, 3, 2, reasons, peaks);

        reasons.add("e");
        peaks.put("emerald", 9);

        assertEquals(List.of("a", "b", "c"), state.reasons(), "the newest few reasons are kept");
        assertEquals(Map.of("diamond", 4), state.peaks(), "a later mutation cannot reach the state");
        assertThrows(UnsupportedOperationException.class, () -> state.reasons().add("x"));
        assertThrows(UnsupportedOperationException.class, () -> state.peaks().put("x", 1));
        assertEquals(BondState.EMPTY, new BondState(-1, 0, -3, null, null), "a corrupt file cannot inflate a bond");
    }

    /** What the gift path does: worth only the growth above the kind's peak, then let the ledger award it. */
    private static boolean closeInventory(BondLedger ledger, String kind, int value, int day) {
        int peak = ledger.snapshot().peaks().getOrDefault(kind, 0);
        return BondRules.giftAward(peak, value) > 0 && ledger.gift(kind, value, day);
    }
}
