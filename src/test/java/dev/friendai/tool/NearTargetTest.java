package dev.friendai.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class NearTargetTest {
    @Test
    void defaultsToTheSpeaker() {
        assertEquals(NearTarget.ME, NearTarget.parse(null));
        assertEquals(NearTarget.ME, NearTarget.parse(" "));
        assertEquals(NearTarget.ME, NearTarget.parse("you"));
    }

    @Test
    void selfIsTheCompanion() {
        assertEquals(NearTarget.SELF, NearTarget.parse("self"));
        assertEquals(NearTarget.SELF, NearTarget.parse("Companion"));
    }

    @Test
    void rejectsUnknownValues() {
        assertThrows(IllegalArgumentException.class, () -> NearTarget.parse("there"));
    }
}
