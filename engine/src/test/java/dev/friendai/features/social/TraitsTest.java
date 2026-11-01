package dev.friendai.features.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TraitsTest {
    @Test
    void aStepRaisesOneTraitAndNeverPastTheCap() {
        Traits traits = Traits.NEUTRAL.step(Traits.Kind.WARMTH);
        assertEquals(1, traits.of(Traits.Kind.WARMTH));
        assertEquals(0, traits.of(Traits.Kind.HUMOUR));
        for (int i = 0; i < 10; i++) {
            traits = traits.step(Traits.Kind.WARMTH);
        }
        assertEquals(Traits.MAX, traits.of(Traits.Kind.WARMTH));
    }

    @Test
    void theNextStepAlwaysGrowsTheSmallestTrait() {
        Traits uneven = new Traits(3, 1, 2, 0, 1);
        assertEquals(Traits.Kind.ENERGY, uneven.next());
        assertEquals(Traits.Kind.HUMOUR, Traits.NEUTRAL.next());
    }

    @Test
    void aDropReturnsTheTraitToNeutralImmediately() {
        Traits traits = new Traits(2, 1, 0, 0, 0).drop(Traits.Kind.HUMOUR);
        assertEquals(0, traits.of(Traits.Kind.HUMOUR));
        assertEquals(1, traits.of(Traits.Kind.WARMTH));
    }

    @Test
    void theToneLineIsEmptyAtNeutralAndCarriesNoNumbers() {
        assertEquals("", Traits.NEUTRAL.toneLine());
        String line = new Traits(3, 1, 0, 0, 0).toneLine();
        assertTrue(line.contains("humour"), line);
        assertTrue(line.chars().noneMatch(Character::isDigit), "no score may reach the prompt: " + line);
    }

    @Test
    void aCorruptValueIsClampedOnConstruction() {
        assertEquals(0, new Traits(-5, 99, 0, 0, 0).of(Traits.Kind.HUMOUR));
        assertEquals(Traits.MAX, new Traits(-5, 99, 0, 0, 0).of(Traits.Kind.WARMTH));
    }

    @Test
    void aRejectedTraitIsDroppedAtOnceAndNeverChosenAgain() {
        Traits drifted = new Traits(2, 1, 0, 0, 0);
        Traits dropped = drifted.drop(Traits.Kind.HUMOUR);
        assertEquals(0, dropped.of(Traits.Kind.HUMOUR), "a rejected trait drops at once");

        var rejected = java.util.Set.of(Traits.Kind.HUMOUR);
        for (int i = 0; i < 8; i++) {
            var next = dropped.nextAllowed(rejected);
            assertTrue(next.isPresent(), "other traits may still grow");
            assertFalse(next.get() == Traits.Kind.HUMOUR, "a rejected trait is never chosen again");
            dropped = dropped.step(next.get());
        }
    }

    @Test
    void droppingEverythingReturnsToNeutral() {
        Traits drifted = new Traits(3, 2, 1, 1, 3);
        assertEquals(Traits.NEUTRAL, drifted.dropAll());
        assertTrue(drifted.dropAll().toneLine().isEmpty());
    }

    @Test
    void nothingMayGrowWhenEveryTraitIsRejected() {
        assertTrue(Traits.NEUTRAL
                .nextAllowed(java.util.EnumSet.allOf(Traits.Kind.class))
                .isEmpty());
    }
}
