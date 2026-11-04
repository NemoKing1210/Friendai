package dev.friendai.features.knowledge.search;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.friendai.foundation.spatial.Pos;
import org.junit.jupiter.api.Test;

class StrongholdClueTest {
    @Test
    void theDominantAxisNamesTheDirection() {
        assertEquals(SearchPlanner.Heading.EAST, StrongholdClue.heading(Pos.of(0, 64, 0), Pos.of(100, 64, 40)));
        assertEquals(SearchPlanner.Heading.WEST, StrongholdClue.heading(Pos.of(0, 64, 0), Pos.of(-120, 64, 30)));
        assertEquals(SearchPlanner.Heading.SOUTH, StrongholdClue.heading(Pos.of(0, 64, 0), Pos.of(10, 64, 200)));
        assertEquals(SearchPlanner.Heading.NORTH, StrongholdClue.heading(Pos.of(0, 64, 0), Pos.of(-10, 64, -200)));
    }
}
