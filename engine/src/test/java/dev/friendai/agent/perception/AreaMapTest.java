package dev.friendai.agent.perception;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AreaMapTest {
    @Test
    void rendersNorthToSouthWestToEast() {
        AreaMap.Glyphs glyphs = (dx, dz) -> {
            if (dx == 0 && dz == 0) return '@';
            if (dz == -1) return '#';
            return '.';
        };
        assertEquals("###\n.@.\n...", AreaMap.render(1, glyphs));
    }

    @Test
    void radiusZeroIsTheCentre() {
        assertEquals("@", AreaMap.render(0, (dx, dz) -> '@'));
    }
}
