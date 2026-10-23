package dev.friendai.crafting.obtain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OreDepthsTest {
    @Test
    void depths() {
        assertEquals(16, OreDepths.depthFor("minecraft:raw_iron").getAsInt());
        assertEquals(-58, OreDepths.depthFor("minecraft:diamond").getAsInt());
        assertEquals(96, OreDepths.depthFor("minecraft:coal").getAsInt());
        assertTrue(OreDepths.depthFor("minecraft:emerald").isEmpty());
    }
}
