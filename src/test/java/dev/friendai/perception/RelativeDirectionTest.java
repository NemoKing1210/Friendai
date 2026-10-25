package dev.friendai.perception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class RelativeDirectionTest {
    @Test
    void compassNamesEightSectors() {
        assertEquals("north", RelativeDirection.compass(0, -5));
        assertEquals("north-east", RelativeDirection.compass(5, -5));
        assertEquals("east", RelativeDirection.compass(5, 0));
        assertEquals("south-east", RelativeDirection.compass(5, 5));
        assertEquals("south", RelativeDirection.compass(0, 5));
        assertEquals("south-west", RelativeDirection.compass(-5, 5));
        assertEquals("west", RelativeDirection.compass(-5, 0));
        assertEquals("north-west", RelativeDirection.compass(-5, -5));
    }

    @Test
    void relativeToUsesMinecraftYaw() {
        assertEquals("ahead", RelativeDirection.relativeTo(-90, 5, 0));      // facing east
        assertEquals("right", RelativeDirection.relativeTo(-90, 0, 5));      // south is right of east
        assertEquals("behind", RelativeDirection.relativeTo(-90, -5, 0));
        assertEquals("left", RelativeDirection.relativeTo(-90, 0, -5));
        assertEquals("ahead-left", RelativeDirection.relativeTo(0, 5, 5));   // facing south
        assertEquals("ahead-right", RelativeDirection.relativeTo(0, -5, 5));
        assertEquals("ahead", RelativeDirection.relativeTo(180, 0, -5));     // facing north
    }
}
