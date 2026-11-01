package dev.friendai.features.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
