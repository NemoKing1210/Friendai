package dev.friendai.features.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
