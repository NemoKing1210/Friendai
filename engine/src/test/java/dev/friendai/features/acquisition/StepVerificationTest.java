package dev.friendai.features.acquisition;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StepVerificationTest {
    private static final ObtainStep MINE = new ObtainStep.Mine("minecraft:oak_log", 3, ToolNeed.HAND);

    @Test
    void aMineThatChangedNothingIsNotProgress() {
        assertFalse(StepVerification.progressed(MINE, false, false, 2, 2));
    }

    @Test
    void aMineThatAddedToTheBagIsProgress() {
        assertTrue(StepVerification.progressed(MINE, false, true, 2, 2));
    }

    @Test
    void aMineThatRevealedASourceIsProgress() {
        assertTrue(StepVerification.progressed(MINE, false, false, 2, 3));
    }

    @Test
    void aMineThatLostASourceIsNotProgress() {
        assertFalse(StepVerification.progressed(MINE, false, false, 3, 2));
    }

    @Test
    void aVisitIsProgressOnlyWhenItVerifiedTheSite() {
        ObtainStep visit = new ObtainStep.VisitResource(7L, "minecraft:overworld", "minecraft:oak_log");

        assertTrue(StepVerification.progressed(visit, true, false, 0, 0));
        assertFalse(StepVerification.progressed(visit, false, true, 0, 9));
    }

    @Test
    void aTraderInspectionIsNotJudgedByCarriedEffects() {
        ObtainStep inspect = new ObtainStep.InspectTrader(java.util.UUID.randomUUID());

        assertTrue(StepVerification.progressed(inspect, false, false, 0, 0));
    }

    @Test
    void aCraftIsJudgedByWhatItProduced() {
        ObtainStep craft = new ObtainStep.Craft("minecraft:oak_planks", 4);

        assertTrue(StepVerification.progressed(craft, false, true, 0, 0));
        assertFalse(StepVerification.progressed(craft, false, false, 0, 0));
    }
}
