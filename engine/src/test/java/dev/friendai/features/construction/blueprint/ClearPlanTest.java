package dev.friendai.features.construction.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ClearPlanTest {
    private static ClearCell cell(int x, int y, int z, ClearKind kind) {
        return new ClearCell(x, y, z, kind);
    }

    @Test
    void kindsComeInOrderAndHigherCellsFirstInsideAKind() {
        List<ClearCell> candidates = List.of(
                cell(0, 5, 0, ClearKind.WATER),
                cell(1, 2, 0, ClearKind.BLOCK),
                cell(2, 9, 0, ClearKind.SNOW),
                cell(3, 1, 0, ClearKind.VEGETATION),
                cell(4, 5, 0, ClearKind.VEGETATION));
        ClearPlan plan = ClearPlan.of(candidates, Set.of(), 10);
        assertEquals(
                List.of(
                        cell(4, 5, 0, ClearKind.VEGETATION),
                        cell(3, 1, 0, ClearKind.VEGETATION),
                        cell(2, 9, 0, ClearKind.SNOW),
                        cell(1, 2, 0, ClearKind.BLOCK),
                        cell(0, 5, 0, ClearKind.WATER)),
                plan.cells());
        assertEquals(0, plan.skipped());
    }

    @Test
    void anUnwantedKindIsSkippedAndCounted() {
        List<ClearCell> candidates = List.of(
                cell(0, 5, 0, ClearKind.WATER),
                cell(1, 2, 0, ClearKind.BLOCK),
                cell(2, 9, 0, ClearKind.SNOW),
                cell(3, 1, 0, ClearKind.VEGETATION));
        ClearPlan plan = ClearPlan.of(candidates, Set.of(ClearKind.BLOCK), 10);
        assertEquals(List.of(cell(1, 2, 0, ClearKind.BLOCK)), plan.cells());
        assertEquals(3, plan.skipped());
    }

    @Test
    void theCapTruncatesAndCountsTheRest() {
        List<ClearCell> candidates = List.of(
                cell(0, 5, 0, ClearKind.VEGETATION),
                cell(1, 4, 0, ClearKind.VEGETATION),
                cell(2, 3, 0, ClearKind.VEGETATION),
                cell(3, 2, 0, ClearKind.VEGETATION),
                cell(4, 1, 0, ClearKind.VEGETATION));
        ClearPlan plan = ClearPlan.of(candidates, Set.of(), 2);
        assertEquals(List.of(cell(0, 5, 0, ClearKind.VEGETATION), cell(1, 4, 0, ClearKind.VEGETATION)), plan.cells());
        assertEquals(3, plan.skipped());
    }

    @Test
    void aDuplicateCellAppearsOnce() {
        ClearCell grass = cell(1, 2, 3, ClearKind.VEGETATION);
        ClearPlan plan = ClearPlan.of(List.of(grass, grass), Set.of(), 10);
        assertEquals(List.of(grass), plan.cells());
        assertEquals(0, plan.skipped());
    }

    @Test
    void theNearestCellOfTheFirstKindIsNext() {
        List<ClearCell> remaining = List.of(
                cell(0, 9, 0, ClearKind.VEGETATION),
                cell(5, 1, 0, ClearKind.VEGETATION),
                cell(1, 1, 0, ClearKind.SNOW));
        assertEquals(1, ClearPlan.nextIndex(remaining, 5, 1, 1));
        assertEquals(0, ClearPlan.nextIndex(remaining, 0, 8, 0));
    }

    @Test
    void aNearerCellOfALaterKindWaitsForTheFirstKind() {
        List<ClearCell> remaining = List.of(cell(9, 1, 0, ClearKind.VEGETATION), cell(0, 1, 0, ClearKind.SNOW));
        assertEquals(0, ClearPlan.nextIndex(remaining, 0, 1, 0));
        assertEquals(-1, ClearPlan.nextIndex(List.of(), 0, 0, 0));
    }

    @Test
    void zeroMeansNothingIsCleared() {
        ClearPlan plan = ClearPlan.of(List.of(cell(0, 5, 0, ClearKind.SNOW)), Set.of(), 0);
        assertEquals(List.of(), plan.cells());
        assertEquals(1, plan.skipped());
    }
}
