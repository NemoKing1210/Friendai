package dev.friendai.features.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class BondRulesTest {
    @Test
    void anOwnerHitCountsOnlyOutsideAFightAndAfterThirtySeconds() {
        assertTrue(BondRules.ownerHitCounts(true, false, BondRules.OWNER_HIT_COOLDOWN_TICKS));
        assertFalse(BondRules.ownerHitCounts(true, true, 10_000), "a hit during a monster fight is not a deed");
        assertFalse(
                BondRules.ownerHitCounts(true, false, BondRules.OWNER_HIT_COOLDOWN_TICKS - 1),
                "two hits inside the window count once");
        assertFalse(BondRules.ownerHitCounts(false, false, 10_000), "no hit, no deed");
    }

    @Test
    void aRescueCountsOnlyWhileTheVictimHitTheOwnerRecently() {
        assertTrue(BondRules.rescueCounts(0));
        assertTrue(BondRules.rescueCounts(BondRules.RESCUE_WINDOW_TICKS), "the window closes after 200 ticks");
        assertFalse(BondRules.rescueCounts(BondRules.RESCUE_WINDOW_TICKS + 1));
        assertFalse(BondRules.rescueCounts(-1), "a victim who never hit the owner is not a rescue");
    }

    @Test
    void aGiftIsWorthOnlyTheGrowthAboveItsPeak() {
        assertEquals(0, BondRules.giftAward(0, 0));
        assertEquals(7, BondRules.giftAward(5, 12));
        assertEquals(0, BondRules.giftAward(12, 12), "the same inventory closed twice is not growth");
        assertEquals(0, BondRules.giftAward(12, 4), "a smaller inventory is not growth");
        assertEquals(12, BondRules.giftAward(-4, 12), "a negative peak reads as no peak");
        assertEquals(0, BondRules.giftAward(3, -1), "negative worth never awards");
    }

    @Test
    void theDayAdvancesOnlyForward() {
        assertTrue(BondRules.dayAdvanced(3, 2));
        assertFalse(BondRules.dayAdvanced(2, 2), "the same day is not a new day");
        assertFalse(BondRules.dayAdvanced(1, 2), "a clock that restarts lower does not move the day back");
    }

    @Test
    void forDayAppliesTheThreeDayDecayCatchUp() {
        BondLedger ledger = new BondLedger();
        assertTrue(ledger.rescue(1));
        assertEquals(5, ledger.score());

        BondLedger caughtUp = BondRules.forDay(ledger, 4);
        assertEquals(2, caughtUp.score(), "three elapsed days drop three points");
        assertEquals(4, caughtUp.snapshot().lastDay());
        assertEquals(List.of("rescue"), caughtUp.reasons(), "decay never drops the reasons");
    }

    @Test
    void forDayResetsTheDailyCounterAndNeverDropsBelowTheBase() {
        BondLedger ledger = new BondLedger();
        for (int i = 0; i < 5; i++) {
            ledger.sharedWork(1);
        }
        assertEquals(BondLedger.DAILY_CAP, ledger.score());

        BondLedger next = BondRules.forDay(ledger, 2);
        assertEquals(BondLedger.DAILY_CAP - 1, next.score(), "one point per elapsed day");
        assertEquals(0, next.snapshot().awardedToday(), "a new day starts with a fresh counter");
        assertTrue(next.sharedWork(2), "the daily cap resets with the day");

        BondLedger far = BondRules.forDay(next, 1_000);
        assertEquals(BondLedger.BASE, far.score(), "decay stops at the base");
    }

    @Test
    void forDayKeepsTheSameLedgerOnTheSameDayAndStartsOneWhenThereIsNone() {
        BondLedger ledger = new BondLedger();
        ledger.gift("diamond", 3, 1);

        BondLedger same = BondRules.forDay(ledger, 1);
        assertSame(ledger, same, "the same day keeps the ledger it was given");
        assertEquals(3, same.score());

        BondLedger fresh = BondRules.forDay(null, 7);
        assertEquals(BondLedger.BASE, fresh.score());
        assertEquals(BondLedger.Tier.STRANGER, fresh.tier());
        assertEquals(7, fresh.snapshot().lastDay());
    }
}
