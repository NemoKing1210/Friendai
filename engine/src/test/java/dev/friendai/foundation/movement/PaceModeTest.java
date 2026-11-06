package dev.friendai.foundation.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PaceModeTest {

    @Test
    void parsesLabelsCaseInsensitively() {
        assertEquals(PaceMode.RUN, PaceMode.parse("run"));
        assertEquals(PaceMode.WALK, PaceMode.parse("WALK"));
    }

    @Test
    void unknownOrNullIsAuto() {
        assertEquals(PaceMode.AUTO, PaceMode.parse(null));
        assertEquals(PaceMode.AUTO, PaceMode.parse("fast"));
    }

    @Test
    void toleratesSurroundingWhitespaceFromAConfigFile() {
        assertEquals(PaceMode.RUN, PaceMode.parse(" run "));
        assertEquals(PaceMode.RUN, PaceMode.parseStrict(" run "));
    }

    @Test
    void aStrictParseRefusesAnUnknownMode() {
        IllegalArgumentException refused =
                assertThrows(IllegalArgumentException.class, () -> PaceMode.parseStrict("sprint"));

        assertEquals("unknown pace mode: sprint", refused.getMessage());
        assertThrows(IllegalArgumentException.class, () -> PaceMode.parseStrict(null));
    }

    @Test
    void labelIsLowerCase() {
        assertEquals("auto", PaceMode.AUTO.label());
        assertEquals("walk", PaceMode.WALK.label());
        assertEquals("run", PaceMode.RUN.label());
    }
}
