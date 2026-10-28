package dev.friendai.perception.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.navigation.pathing.Pos;
import java.util.List;
import org.junit.jupiter.api.Test;

class CaveEntranceDetectorTest {
    @Test
    void detectsOpenDescendingSightCorridor() {
        List<Pos> clearCells =
                List.of(new Pos(0, 12, 0), new Pos(1, 11, 0), new Pos(2, 10, 0), new Pos(3, 9, 0), new Pos(4, 8, 0));

        var entrance = CaveEntranceDetector.detect(clearCells, new Pos(0, 12, 0), new Pos(5, 8, 0))
                .orElseThrow();

        assertEquals(new Pos(1, 11, 0), entrance.approach());
        assertEquals(new Pos(2, 10, 0), entrance.opening());
        assertEquals(new Pos(4, 8, 0), entrance.interior());
    }

    @Test
    void doesNotTreatShallowVisibleDipAsCaveEntrance() {
        List<Pos> clearCells = List.of(new Pos(0, 12, 0), new Pos(1, 11, 0), new Pos(2, 11, 0));

        assertTrue(CaveEntranceDetector.detect(clearCells, new Pos(0, 12, 0), new Pos(3, 11, 0))
                .isEmpty());
    }

    @Test
    void requiresOpenCellsBelowTheOpeningAndVisibleInteriorWall() {
        assertTrue(CaveEntranceDetector.detect(
                        List.of(new Pos(0, 12, 0), new Pos(1, 10, 0), new Pos(2, 9, 0)),
                        new Pos(0, 12, 0),
                        new Pos(3, 8, 0))
                .isEmpty());
        assertTrue(CaveEntranceDetector.detect(
                        List.of(new Pos(0, 12, 0), new Pos(1, 10, 0), new Pos(2, 9, 0), new Pos(3, 8, 0)),
                        new Pos(0, 12, 0),
                        null)
                .isEmpty());
    }
}
