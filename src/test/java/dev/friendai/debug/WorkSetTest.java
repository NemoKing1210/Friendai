package dev.friendai.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkSetTest {
    @Test
    void rejectsCountsThatDoNotMatchTheWork() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkSet("ore", WorkKind.BLOCKS, 3, 0, 1, 1, 0, false, List.of()));
    }

    @Test
    void copiesCellsAndKeepsStatusCounts() {
        WorkCell cell = new WorkCell(2, 64, 3, DebugAction.BREAK, WorkStatus.NEXT, "ore");
        List<WorkCell> cells = new ArrayList<>(List.of(cell));
        WorkSet set = new WorkSet("ore vein", WorkKind.BLOCKS, 1, 0, 1, 0, 0, false, cells);
        cells.clear();

        assertEquals(List.of(cell), set.cells());
        assertEquals(1, set.next());
        assertThrows(UnsupportedOperationException.class, () -> set.cells().clear());
    }

    @Test
    void rejectsRepresentedCellsThatExceedTheirStatusCount() {
        WorkCell cell = new WorkCell(0, 0, 0, DebugAction.BREAK, WorkStatus.DONE, "ore");

        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkSet("ore", WorkKind.BLOCKS, 1, 0, 0, 1, 0, false, List.of(cell)));
    }

    @Test
    void acceptsAnEmptyWorkSet() {
        WorkSet set = new WorkSet("tree", WorkKind.BLOCKS, 0, 0, 0, 0, 0, false, List.of());

        assertEquals(0, set.total());
        assertEquals(List.of(), set.cells());
    }
}
