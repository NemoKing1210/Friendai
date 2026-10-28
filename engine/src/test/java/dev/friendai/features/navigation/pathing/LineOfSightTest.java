package dev.friendai.features.navigation.pathing;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.testutil.GridCellView;
import org.junit.jupiter.api.Test;

class LineOfSightTest {

    private static final double EYE_X = 0.5;
    private static final double EYE_Y = 1.62;
    private static final double EYE_Z = 0.5;

    @Test
    void clearAlongAxis() {
        GridCellView v = new GridCellView("#####", ".....");
        assertTrue(LineOfSight.clear(v, EYE_X, EYE_Y, EYE_Z, new Pos(3, 1, 0)));
    }

    @Test
    void blockedByGlass() {
        GridCellView v = new GridCellView("#####", "..G..");
        assertFalse(LineOfSight.clear(v, EYE_X, EYE_Y, EYE_Z, new Pos(3, 1, 0)));
    }

    @Test
    void clearDiagonal() {
        GridCellView v = new GridCellView("#####/#####", "...../.....", "...../.....", "...../.....");
        assertTrue(LineOfSight.clear(v, EYE_X, EYE_Y, EYE_Z, new Pos(2, 3, 2)));
    }

    @Test
    void blockedDiagonal() {
        GridCellView v = new GridCellView("#####/#####", "...../.....", "...../.#...", "...../.....");
        assertFalse(LineOfSight.clear(v, EYE_X, EYE_Y, EYE_Z, new Pos(2, 3, 2)));
    }

    @Test
    void adjacentTargetIsClear() {
        GridCellView v = new GridCellView("#####", ".....");
        assertTrue(LineOfSight.clear(v, EYE_X, EYE_Y, EYE_Z, new Pos(1, 1, 0)));
    }
}
