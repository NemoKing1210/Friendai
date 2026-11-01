package dev.friendai.features.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.friendai.features.navigation.pathing.Pos;
import dev.friendai.testutil.GridCellView;
import org.junit.jupiter.api.Test;

class MiningSafetyFloorRiskTest {
    @Test
    void lavaDirectlyBelowTheTargetIsARisk() {
        GridCellView view = new GridCellView("##L", "###", "...");
        assertEquals(
                MiningSafety.FloorRisk.LAVA_BELOW_TARGET,
                MiningSafety.floorRisk(view, new Pos(2, 1, 0), new Pos(1, 1, 0), null));
    }

    @Test
    void lavaBelowTheStanceIsARisk() {
        GridCellView view = new GridCellView("#L#", "###", "...");
        assertEquals(
                MiningSafety.FloorRisk.LAVA_BELOW_STANCE,
                MiningSafety.floorRisk(view, new Pos(0, 1, 0), new Pos(1, 1, 0), null));
    }

    @Test
    void lavaBehindTheTargetIsARisk() {
        GridCellView view = new GridCellView("####", "###L", "....");
        assertEquals(
                MiningSafety.FloorRisk.LAVA_BEHIND,
                MiningSafety.floorRisk(view, new Pos(1, 1, 0), new Pos(0, 1, 0), new Pos(3, 1, 0)));
    }

    @Test
    void aStanceWithNoSupportIsARisk() {
        GridCellView view = new GridCellView("#.#", "###", "...");
        assertEquals(
                MiningSafety.FloorRisk.NO_SUPPORT_AFTER,
                MiningSafety.floorRisk(view, new Pos(0, 1, 0), new Pos(1, 1, 0), null));
    }

    @Test
    void aSolidFloorIsSafe() {
        GridCellView view = new GridCellView("###", "###", "...");
        assertEquals(
                MiningSafety.FloorRisk.SAFE,
                MiningSafety.floorRisk(view, new Pos(0, 1, 0), new Pos(1, 1, 0), new Pos(2, 1, 0)));
    }
}
