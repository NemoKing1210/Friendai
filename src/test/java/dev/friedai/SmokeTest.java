package dev.friedai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SmokeTest {
    @Test
    void modIdIsFriedai() {
        assertEquals("friedai", FriedaiMod.MOD_ID);
    }
}
