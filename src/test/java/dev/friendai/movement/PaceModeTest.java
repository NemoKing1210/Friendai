package dev.friendai.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void labelIsLowerCase() {
        assertEquals("auto", PaceMode.AUTO.label());
        assertEquals("walk", PaceMode.WALK.label());
        assertEquals("run", PaceMode.RUN.label());
    }
}
