package dev.friendai.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.testutil.GridCellView;
import org.junit.jupiter.api.Test;

class GridCellViewTest {

    @Test
    void parsesLayersRowsAndColumns() {
        GridCellView v = new GridCellView("###/###", "...", "~L?");
        assertEquals(Cell.Kind.SOLID, v.at(0, 0, 0).kind());
        assertEquals(30, v.at(0, 0, 0).breakTicks());
        assertEquals(Cell.Kind.LAVA, v.at(1, 2, 0).kind());
        assertEquals(Cell.Kind.UNLOADED, v.at(5, 0, 0).kind());
        assertTrue(v.at(0, 1, 0).placeable());
    }

    @Test
    void waterIsPlaceableAndClimbableSupports() {
        GridCellView v = new GridCellView("~H");
        assertTrue(v.at(0, 0, 0).placeable());
        assertEquals(Cell.Kind.CLIMBABLE, v.at(1, 0, 0).kind());
        assertTrue(v.at(1, 0, 0).supports());
    }

    @Test
    void protectMakesACellUnbreakableAndUnplaceable() {
        GridCellView v = new GridCellView("..").protect(new Pos(1, 0, 0));
        assertEquals(-1, v.at(1, 0, 0).breakTicks());
        assertFalse(v.at(1, 0, 0).placeable());
    }
}
