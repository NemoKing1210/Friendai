package dev.friendai.features.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BondLedgerTest {
    @Test
    void aGiftCountsOnlyWhenTheKindGrowsPastItsPeak() {
        BondLedger bond = new BondLedger();
        assertTrue(bond.gift("diamond", 3, 1));
        assertFalse(bond.gift("diamond", 2, 1), "a smaller later balance is not a new gift");
        assertFalse(bond.gift("diamond", 3, 1), "the same peak is not a new gift");
        assertTrue(bond.gift("diamond", 4, 1));
        assertEquals(6, bond.score());
    }

    @Test
    void theDailyCapStopsFarmingAndDecayStillWorks() {
        BondLedger bond = new BondLedger();
        for (int i = 1; i <= 10; i++) {
            bond.sharedWork(1);
        }
        assertEquals(BondLedger.DAILY_CAP, bond.score());
        bond.advanceDay(3);
        assertEquals(BondLedger.DAILY_CAP - 2, bond.score(), "one point per elapsed day");
        bond.advanceDay(100);
        assertEquals(BondLedger.BASE, bond.score());
    }

    @Test
    void tiersFollowTheScoreAndReasonsStayBounded() {
        BondLedger bond = new BondLedger();
        bond.rescue(1);
        bond.advanceDay(2);
        bond.rescue(2);
        bond.advanceDay(3);
        bond.rescue(3);
        assertEquals(BondLedger.Tier.ACQUAINTANCE, bond.tier());
        assertTrue(bond.reasons().size() <= 3);
        for (int day = 4; day <= 8; day++) {
            bond.rescue(day);
        }
        assertEquals(BondLedger.Tier.FRIEND, bond.tier());
        assertEquals(3, bond.reasons().size());
    }

    @Test
    void anOwnerHitLowersTheScoreByTwoAndIsRemembered() {
        BondLedger bond = new BondLedger();
        bond.gift("diamond", 1, 1);
        assertEquals(3, bond.score());

        assertTrue(bond.hurtByOwner(1));
        assertEquals(1, bond.score(), "an owner hit costs two points");
        assertEquals(List.of("owner hit", "gift: diamond"), bond.reasons(), "the newest reason comes first");

        assertTrue(bond.hurtByOwner(1));
        assertEquals(BondLedger.BASE, bond.score(), "the score never drops below the base");
    }

    @Test
    void anOwnerHitIsRefusedOnceTheDaysAwardsFilledTheCap() {
        BondLedger bond = new BondLedger();
        for (int i = 0; i < 3; i++) {
            assertTrue(bond.sharedWork(1), "three shared jobs fill the cap of six");
        }
        assertEquals(BondLedger.DAILY_CAP, bond.score());

        assertFalse(bond.hurtByOwner(1), "a hit next to a full day of awards would spend past the cap");
        assertEquals(BondLedger.DAILY_CAP, bond.score());
        assertFalse(bond.reasons().contains("owner hit"), "a refused hit leaves no reason behind: " + bond.reasons());
    }

    @Test
    void tiersChangeExactlyAtTheirThresholds() {
        assertEquals(BondLedger.Tier.STRANGER, bondAt(0).tier());
        assertEquals(
                BondLedger.Tier.STRANGER, bondAt(BondLedger.ACQUAINTANCE - 1).tier());
        assertEquals(
                BondLedger.Tier.ACQUAINTANCE, bondAt(BondLedger.ACQUAINTANCE).tier());
        assertEquals(BondLedger.Tier.ACQUAINTANCE, bondAt(BondLedger.FRIEND - 1).tier());
        assertEquals(BondLedger.Tier.FRIEND, bondAt(BondLedger.FRIEND).tier());
        assertEquals(BondLedger.Tier.FRIEND, bondAt(BondLedger.CLOSE - 1).tier());
        assertEquals(BondLedger.Tier.CLOSE, bondAt(BondLedger.CLOSE).tier());
        assertEquals(BondLedger.Tier.CLOSE, bondAt(10_000).tier());
    }

    @Test
    void oneDayOfDecayCanDropTheTier() {
        BondLedger bond = bondAt(BondLedger.ACQUAINTANCE);
        bond.advanceDay(1);
        assertEquals(BondLedger.ACQUAINTANCE - 1, bond.score());
        assertEquals(BondLedger.Tier.STRANGER, bond.tier(), "the threshold is not rounded up");
    }

    /** A ledger restored from a save at a known score, so a tier boundary is exact without farming deeds. */
    private static BondLedger bondAt(int points) {
        BondLedger bond = new BondLedger();
        bond.restore(new BondState(points, 0, 0, List.of(), Map.of()));
        return bond;
    }
}
