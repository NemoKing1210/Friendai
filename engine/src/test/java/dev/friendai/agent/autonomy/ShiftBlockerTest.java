package dev.friendai.agent.autonomy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class ShiftBlockerTest {
    private static final String CLIMB = "there is no clear side to climb the spruce tree at -1 80 46: "
            + "the foot of the climb at -2 73 45 is protected in 2 ticks";

    @Test
    void theSameCauseOnAnotherTreeAndAnotherTickIsTheSameBlocker() {
        String later = CLIMB.replace("-1 80 46", "-30 70 12")
                .replace("-2 73 45", "-31 63 11")
                .replace("in 2 ticks", "in 41 ticks");
        assertEquals(ShiftBlocker.key(CLIMB), ShiftBlocker.key(later));
    }

    @Test
    void aDifferentCauseStaysADifferentBlocker() {
        String support = "-5 84 31 needs a block at -5 79 31 that the blueprint does not provide in 3 ticks";
        assertNotEquals(ShiftBlocker.key(CLIMB), ShiftBlocker.key(support));
    }

    @Test
    void countersAndCaseDoNotChangeTheBlocker() {
        String summary =
                "RAN OUT OF TIME in 400 ticks; 3 scaffold blocks placed; 2 logs could not be cut (out of reach at 1 2 3)";
        assertEquals("ran out of time", ShiftBlocker.key(summary));
    }

    @Test
    void nullAndBlankAreEmpty() {
        assertEquals("", ShiftBlocker.key(null));
        assertEquals("", ShiftBlocker.key("   "));
    }
}
