package dev.friendai.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WeaponPolicyTest {

    @Test
    void balancedPrefersBestSafeDps() {
        WeaponPolicy.Score sword = WeaponPolicy.score(
                WeaponPolicy.Facts.melee(8.0, 3.0, 0.8, false, true, 2.0, true), FightingStyle.BALANCED);
        WeaponPolicy.Score axe = WeaponPolicy.score(
                WeaponPolicy.Facts.melee(6.0, 3.0, 0.8, true, false, 2.0, true), FightingStyle.BALANCED);
        assertTrue(sword.utility() > axe.utility(), "sword " + sword.utility() + " vs axe " + axe.utility());
        assertEquals(WeaponPolicy.Intent.STRIKE, sword.intent());
    }

    @Test
    void unsafeWeaponScoresMuchLower() {
        WeaponPolicy.Score safe = WeaponPolicy.score(
                WeaponPolicy.Facts.melee(8.0, 3.0, 0.8, false, true, 2.0, true), FightingStyle.BALANCED);
        WeaponPolicy.Score unsafe = WeaponPolicy.score(
                WeaponPolicy.Facts.melee(8.0, 3.0, 0.8, false, true, 2.0, false), FightingStyle.BALANCED);
        assertTrue(safe.utility() > unsafe.utility());
    }

    @Test
    void cautiousPrefersSafeDistance() {
        WeaponPolicy.Score score = WeaponPolicy.score(
                WeaponPolicy.Facts.melee(8.0, 3.0, 0.8, false, true, 2.0, true), FightingStyle.CAUTIOUS);
        assertTrue(score.preferredDistance() > 3.0, "cautious distance was " + score.preferredDistance());
        assertEquals(WeaponPolicy.Intent.KEEP_RANGE, score.intent());
    }

    @Test
    void cautiousBlocksWhenThePositionIsUnsafe() {
        WeaponPolicy.Score score = WeaponPolicy.score(
                WeaponPolicy.Facts.melee(8.0, 3.0, 0.8, false, true, 2.0, false), FightingStyle.CAUTIOUS);
        assertEquals(WeaponPolicy.Intent.BLOCK, score.intent());
    }

    @Test
    void meleeStyleClosesWhenSafe() {
        WeaponPolicy.Score score = WeaponPolicy.score(
                WeaponPolicy.Facts.melee(8.0, 3.0, 0.8, false, true, 7.0, true), FightingStyle.MELEE);
        assertEquals(WeaponPolicy.Intent.CLOSE, score.intent());
        WeaponPolicy.Score inReach = WeaponPolicy.score(
                WeaponPolicy.Facts.melee(8.0, 3.0, 0.8, false, true, 2.0, true), FightingStyle.MELEE);
        assertEquals(WeaponPolicy.Intent.STRIKE, inReach.intent());
    }

    @Test
    void rangedStyleKeepsUsefulRange() {
        WeaponPolicy.Score score = WeaponPolicy.score(
                WeaponPolicy.Facts.bow(0.9, 10.0, true, false), FightingStyle.RANGED);
        assertTrue(score.usable());
        assertEquals(WeaponPolicy.Intent.KEEP_RANGE, score.intent());
        assertEquals(WeaponPolicy.BOW_DISTANCE, score.preferredDistance(), 1e-9);

        WeaponPolicy.Score meleeWithBow = WeaponPolicy.score(
                WeaponPolicy.Facts.melee(8.0, 3.0, 0.8, false, true, 2.0, true), FightingStyle.RANGED);
        assertTrue(score.utility() > meleeWithBow.utility());
    }

    @Test
    void hysteresisPreventsDistanceOscillation() {
        WeaponPolicy.Band band = WeaponPolicy.band(2.9, 3.0, WeaponPolicy.Band.UNKNOWN);
        assertEquals(WeaponPolicy.Band.IN_REACH, band);
        // Just beyond reach: still inside until the leave threshold.
        assertEquals(WeaponPolicy.Band.IN_REACH, WeaponPolicy.band(3.5, 3.0, band));
        assertEquals(WeaponPolicy.Band.IN_REACH, WeaponPolicy.band(3.9, 3.0, band));
        band = WeaponPolicy.band(4.1, 3.0, band);
        assertEquals(WeaponPolicy.Band.OUT_OF_REACH, band);
        // Inside the hysteresis band the previous band is kept.
        assertEquals(WeaponPolicy.Band.OUT_OF_REACH, WeaponPolicy.band(3.7, 3.0, WeaponPolicy.Band.OUT_OF_REACH));
        assertEquals(WeaponPolicy.Band.IN_REACH, WeaponPolicy.band(3.7, 3.0, WeaponPolicy.Band.IN_REACH));
        // Coming back needs to be inside the enter threshold.
        assertEquals(WeaponPolicy.Band.IN_REACH, WeaponPolicy.band(3.4, 3.0, WeaponPolicy.Band.OUT_OF_REACH));
        assertEquals(WeaponPolicy.Band.IN_REACH, WeaponPolicy.band(3.4, 3.0, WeaponPolicy.Band.IN_REACH));
        assertEquals(WeaponPolicy.Band.OUT_OF_REACH, WeaponPolicy.band(3.4, 3.0, WeaponPolicy.Band.UNKNOWN));
    }

    @Test
    void damagedWeaponFallsBack() {
        WeaponPolicy.Score score = WeaponPolicy.score(
                WeaponPolicy.Facts.melee(12.0, 4.0, 0.05, false, true, 2.0, true), FightingStyle.BALANCED);
        assertFalse(score.usable());
        assertEquals(WeaponPolicy.Intent.FALLBACK, score.intent());
        assertEquals(0.0, score.utility(), 1e-9);
    }
}
