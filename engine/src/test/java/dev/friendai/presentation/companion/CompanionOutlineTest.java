package dev.friendai.presentation.companion;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CompanionOutlineTest {
    private static final double NEAR = 4.0 * 4.0;
    private static final double FAR = 20.0 * 20.0;

    @Test
    void hiddenWhileTheCompanionIsNearAndInSight() {
        assertFalse(CompanionOutline.shouldDraw(true, true, NEAR, true));
    }

    @Test
    void drawnWhenTheCompanionIsOutOfSightEvenCloseUp() {
        assertTrue(CompanionOutline.shouldDraw(true, true, NEAR, false));
    }

    @Test
    void drawnWhenTheCompanionIsFarAwayEvenInPlainSight() {
        assertTrue(CompanionOutline.shouldDraw(true, true, FAR, true));
    }

    @Test
    void drawnWhenFarAndHidden() {
        assertTrue(CompanionOutline.shouldDraw(true, true, FAR, false));
    }

    @Test
    void farLimitIsExclusive() {
        double atLimit = CompanionOutline.FAR_BLOCKS * CompanionOutline.FAR_BLOCKS;
        assertFalse(CompanionOutline.shouldDraw(true, true, atLimit, true));
        assertTrue(CompanionOutline.shouldDraw(true, true, Math.nextUp(atLimit), true));
    }

    @Test
    void offToggleHidesTheBoxWhateverTheDistanceOrSight() {
        assertFalse(CompanionOutline.shouldDraw(false, true, FAR, false));
        assertFalse(CompanionOutline.shouldDraw(false, true, NEAR, true));
    }

    @Test
    void anotherPlayersCompanionIsNeverOutlined() {
        assertFalse(CompanionOutline.shouldDraw(true, false, FAR, false));
        assertFalse(CompanionOutline.shouldDraw(true, false, NEAR, true));
    }

    @Test
    void cheapGateAllowsOnlyAnEnabledOwnersCompanion() {
        assertFalse(CompanionOutline.applies(false, true));
        assertFalse(CompanionOutline.applies(true, false));
        assertTrue(CompanionOutline.applies(true, true));
    }
}
