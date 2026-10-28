package dev.friendai.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkSetBudgetTest {
    @Test
    void retainsNextAndNearestPendingWithDeterministicBoundaryTie() {
        List<WorkCell> cells = new ArrayList<>();
        cells.add(cell(1000, 64, 0, WorkStatus.NEXT));
        for (int x = 1; x <= 254; x++) {
            cells.add(cell(x, 64, 0, WorkStatus.PENDING));
        }
        cells.add(cell(300, 64, 0, WorkStatus.PENDING));
        cells.add(cell(-300, 64, 0, WorkStatus.PENDING));
        cells.add(cell(0, 64, 300, WorkStatus.PENDING));
        for (int x = 1001; x <= 1042; x++) {
            cells.add(cell(x, 64, 0, WorkStatus.PENDING));
        }
        WorkSet set = new WorkSet("vein", WorkKind.BLOCKS, 300, 0, 1, 299, 0, false, cells);

        WorkSet selected = WorkSetBudget.select(List.of(set), 0, 64, 0).getFirst();

        assertEquals(256, selected.cells().size());
        assertEquals(1000, selected.cells().getFirst().x());
        assertTrue(selected.cells().contains(cell(-300, 64, 0, WorkStatus.PENDING)));
        assertTrue(!selected.cells().contains(cell(300, 64, 0, WorkStatus.PENDING))
                && !selected.cells().contains(cell(0, 64, 300, WorkStatus.PENDING)));
        assertEquals(300, selected.total());
        assertEquals(299, selected.pending());
        assertTrue(selected.truncated());
    }

    @Test
    void reservesNextCellForEachSetBeforePendingCellsFillTheBudget() {
        List<WorkCell> firstCells = new ArrayList<>();
        firstCells.add(cell(1000, 64, 0, WorkStatus.NEXT));
        for (int x = 1; x <= 255; x++) {
            firstCells.add(cell(x, 64, 0, WorkStatus.PENDING));
        }
        List<WorkCell> secondCells = List.of(cell(-1000, 64, 0, WorkStatus.NEXT),
                cell(-1, 64, 0, WorkStatus.PENDING));
        WorkSet first = new WorkSet("first", WorkKind.BLOCKS, 256, 0, 1, 255, 0, false, firstCells);
        WorkSet second = new WorkSet("second", WorkKind.BLOCKS, 2, 0, 1, 1, 0, false, secondCells);

        List<WorkSet> result = WorkSetBudget.select(List.of(first, second), 0, 64, 0);

        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(set -> set.cells().stream()
                .anyMatch(cell -> cell.status() == WorkStatus.NEXT)));
        assertEquals(256, result.stream().mapToInt(set -> set.cells().size()).sum());
    }

    private static WorkCell cell(int x, int y, int z, WorkStatus status) {
        return new WorkCell(x, y, z, DebugAction.BREAK, status, "ore");
    }
}
