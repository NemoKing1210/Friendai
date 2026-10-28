package dev.friendai.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ShieldPolicyTest {

    @Test
    void frontalArrowInFlightRaisesThenKeepsTheShield() {
        assertEquals(ShieldPolicy.Need.RAISE, ShieldPolicy.decide(true, false, true, false, true, true));
        assertEquals(ShieldPolicy.Need.KEEP, ShieldPolicy.decide(true, true, true, false, true, false));
    }

    @Test
    void windupOutsideReachRaisesBeforeTheArrowExists() {
        assertEquals(ShieldPolicy.Need.RAISE, ShieldPolicy.decide(true, false, false, true, true, false));
        assertEquals(ShieldPolicy.Need.KEEP, ShieldPolicy.decide(true, true, false, true, true, false));
    }

    @Test
    void windupInReachLowersTheShieldToSwing() {
        assertEquals(ShieldPolicy.Need.LOWER, ShieldPolicy.decide(true, true, false, true, true, true));
    }

    @Test
    void frontalArrowInReachStillBlocks() {
        assertEquals(ShieldPolicy.Need.RAISE, ShieldPolicy.decide(true, false, true, false, true, true));
    }

    @Test
    void arrowFromBehindIsNotBlocked() {
        assertEquals(ShieldPolicy.Need.LOWER, ShieldPolicy.decide(true, false, true, false, false, false));
    }

    @Test
    void noShieldNeverRaises() {
        assertEquals(ShieldPolicy.Need.LOWER, ShieldPolicy.decide(false, false, true, true, true, false));
        assertEquals(ShieldPolicy.Need.LOWER, ShieldPolicy.decide(false, false, false, true, true, false));
    }
}
