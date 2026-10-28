package dev.friendai.features.navigation.pathing;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.testutil.GridCellView;
import org.junit.jupiter.api.Test;

class LineOfSightFaceTest {

    @Test
    void blockBesideTheFeetUnderStoneIsInViewThroughItsSideFace() {
        // A tunnel running along z at y=1..2; the ore sits in the end wall at feet level with stone above it.
        GridCellView view = new GridCellView("ddd/ddd/ddd", "ddd/d.d/ddd", "ddd/d.d/ddd", "ddd/ddd/ddd");
        Pos ore = new Pos(1, 1, 2);

        assertTrue(LineOfSight.clear(view, 1.5, 1 + 1.62, 1.5, ore));
    }

    @Test
    void blockBehindAWallStaysHidden() {
        GridCellView view =
                new GridCellView("ddd/ddd/ddd/ddd", "ddd/d.d/ddd/ddd", "ddd/d.d/ddd/ddd", "ddd/ddd/ddd/ddd");

        assertFalse(LineOfSight.clear(view, 1.5, 1 + 1.62, 1.5, new Pos(1, 1, 3)));
    }
}
