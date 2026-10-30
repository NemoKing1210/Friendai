package dev.friendai.features.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.navigation.pathing.Pos;
import dev.friendai.testutil.GridCellView;
import java.util.List;
import org.junit.jupiter.api.Test;

class DropRiskTest {
    private static final int MAX_BLOCKS = 9;

    /** A five by three floor: the case where the target already has something under it. */
    private static GridCellView floor() {
        return new GridCellView("#####/#####/#####", "...../...../.....", "...../...../.....");
    }

    @Test
    void solidRockUnderTheTargetNeedsNothing() {
        DropRisk.Report report = DropRisk.assess(floor(), new Pos(2, 1, 0), p -> true, MAX_BLOCKS);

        assertEquals(DropRisk.Verdict.SAFE, report.verdict());
        assertEquals(DropRisk.Reason.LANDS_BELOW, report.reason());
        assertEquals(new Pos(2, 0, 0), report.landing());
        assertTrue(report.catchCells().isEmpty());
    }

    @Test
    void shallowHoleStillLandsInReach() {
        // One open cell under the target, rock under that: the drop lands within the sweep's reach.
        DropRisk.Report report = DropRisk.assess(floor(), new Pos(2, 2, 0), p -> true, MAX_BLOCKS);

        assertEquals(DropRisk.Verdict.SAFE, report.verdict());
        assertEquals(DropRisk.Reason.CATCHES_DROP, report.reason());
        assertEquals(new Pos(2, 0, 0), report.landing());
    }

    @Test
    void lavaDirectlyBelowNeedsTheCellUnderTheTarget() {
        GridCellView view = new GridCellView("#####/#####/#####", "..L../...../.....", "...../...../.....");

        DropRisk.Report report = DropRisk.assess(view, new Pos(2, 2, 0), p -> true, MAX_BLOCKS);

        assertEquals(DropRisk.Verdict.NEEDS_PLATFORM, report.verdict());
        assertEquals(DropRisk.Reason.DESTROYS_DROP, report.reason());
        assertEquals(List.of(new Pos(2, 1, 0)), report.catchCells());
    }

    @Test
    void fireUnderAColumnIsCaughtAboveIt() {
        GridCellView view =
                new GridCellView("...../...../.....", "..^../...../.....", ".#.../...../.....", "...../...../.....");
        // The target sits one cell above a fire cell with one open cell in between.
        DropRisk.Report report = DropRisk.assess(view, new Pos(2, 3, 0), p -> true, MAX_BLOCKS);

        assertEquals(DropRisk.Verdict.NEEDS_PLATFORM, report.verdict());
        assertEquals(DropRisk.Reason.DESTROYS_DROP, report.reason());
        assertEquals(new Pos(2, 2, 0), report.catchCells().get(0), "the catch must sit above the fire");
    }

    @Test
    void aCliffEdgeFillsTheSideThatWouldSlideOff() {
        // Rock only under the far left; the target hangs over empty air with one wall to build against.
        GridCellView view = new GridCellView(
                "#####/#####/#####",
                "...../...../.....",
                "...../...../.....",
                "...../...../.....",
                ".#.../...../.....",
                "...../...../.....");

        DropRisk.Report report = DropRisk.assess(view, new Pos(2, 5, 0), p -> true, MAX_BLOCKS);

        assertEquals(DropRisk.Verdict.NEEDS_PLATFORM, report.verdict());
        assertEquals(DropRisk.Reason.DEEP_BELOW, report.reason());
        List<Pos> cells = report.catchCells();
        assertEquals(new Pos(2, 4, 0), cells.get(0), "the cell under the target is placed first");
        assertTrue(cells.contains(new Pos(3, 4, 0)), "the open side must be filled: " + cells);
        assertTrue(cells.contains(new Pos(2, 4, 1)), "the open side must be filled: " + cells);
        assertTrue(cells.contains(new Pos(3, 4, 1)), "the diagonal a sliding drop leaves by must be filled: " + cells);
        assertFalse(cells.contains(new Pos(1, 4, 0)), "a solid side is not built in");
    }

    @Test
    void anEmptyShaftUnderTheOreGetsAPlatformAgainstTheOreItself() {
        // The ore's own bottom face is a legal face to click, so a ravine is buildable even with no wall in reach.
        GridCellView view = new GridCellView(
                "...../...../.....",
                "...../...../.....",
                "...../...../.....",
                "...../...../.....",
                "...../...../.....",
                "..#../...../.....");

        DropRisk.Report report = DropRisk.assess(view, new Pos(2, 5, 0), p -> true, MAX_BLOCKS);

        assertEquals(DropRisk.Verdict.NEEDS_PLATFORM, report.verdict());
        assertEquals(new Pos(2, 4, 0), report.catchCells().get(0));
    }

    @Test
    void unloadedBelowIsTheVoid() {
        DropRisk.Report report = DropRisk.assess(new GridCellView("...../...../....."), new Pos(2, 1, 0), p -> true, 9);

        assertEquals(DropRisk.Verdict.IMPOSSIBLE, report.verdict());
        assertEquals(DropRisk.Reason.VOID_BELOW, report.reason());
    }

    @Test
    void aProtectedCellIsNeverBuiltIn() {
        GridCellView view = new GridCellView(
                "#####/#####/#####",
                "...../...../.....",
                "...../...../.....",
                "...../...../.....",
                ".#.../...../.....",
                "...../...../.....");
        Pos below = new Pos(2, 4, 0);
        view.protect(below);

        DropRisk.Report report = DropRisk.assess(view, new Pos(2, 5, 0), p -> true, MAX_BLOCKS);

        assertEquals(DropRisk.Verdict.IMPOSSIBLE, report.verdict());
        assertEquals(DropRisk.Reason.PROTECTED_CELL, report.reason());
    }

    @Test
    void aPlaceTheCompanionMayNotBuildInIsRefused() {
        DropRisk.Report report = DropRisk.assess(floor(), new Pos(2, 2, 0), p -> false, MAX_BLOCKS);

        assertTrue(report.verdict() == DropRisk.Verdict.SAFE, "a solid floor needs no build at all");

        GridCellView lava = new GridCellView("#####/#####/#####", "..L../...../.....", "...../...../.....");
        DropRisk.Report refused = DropRisk.assess(lava, new Pos(2, 2, 0), p -> false, MAX_BLOCKS);

        assertEquals(DropRisk.Verdict.IMPOSSIBLE, refused.verdict());
        assertEquals(DropRisk.Reason.PROTECTED_CELL, refused.reason());
    }

    @Test
    void aPlatformWiderThanTheCapIsRefusedRatherThanHalfBuilt() {
        GridCellView view = new GridCellView(
                "#####/#####/#####",
                "...../...../.....",
                "...../...../.....",
                "...../...../.....",
                ".#.../...../.....",
                "...../...../.....");

        DropRisk.Report report = DropRisk.assess(view, new Pos(2, 5, 0), p -> true, 2);

        assertEquals(DropRisk.Verdict.IMPOSSIBLE, report.verdict());
        assertEquals(DropRisk.Reason.TOO_MANY_BLOCKS, report.reason());
        assertTrue(report.catchCells().isEmpty(), "a refused platform must not come back as cells to place");
    }

    @Test
    void aWaterfallNeedsAPlatformButAFloorUnderWaterDoesNot() {
        GridCellView deep = new GridCellView(
                "#####/#####/#####",
                "~~~~~/~~~~~/~~~~~",
                "~~~~~/~~~~~/~~~~~",
                "~~~~~/~~~~~/~~~~~",
                "##.../...../.....",
                "...../...../.....");

        DropRisk.Report report = DropRisk.assess(deep, new Pos(2, 5, 0), p -> true, MAX_BLOCKS);

        assertEquals(DropRisk.Verdict.NEEDS_PLATFORM, report.verdict(), "a moving water column carries the drop");
        assertEquals(new Pos(2, 4, 0), report.catchCells().get(0));

        GridCellView pond =
                new GridCellView("#####/#####/#####", "~~~~~/~~~~~/~~~~~", "~~~~~/~~~~~/~~~~~", "#####/#####/#####");
        assertEquals(
                DropRisk.Verdict.SAFE,
                DropRisk.assess(pond, new Pos(2, 3, 0), p -> true, MAX_BLOCKS).verdict(),
                "a shallow pond leaves the drop one cell down");
    }
}
