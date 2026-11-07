package dev.friendai.features.mining.minecraft.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.task.TaskStatus;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class FellTreeTaskTest {
    @Test
    void eightOfNineLogsIsASuccessWithTheShortfallNamed() {
        String report = FellTreeTask.report(8, 9, "oak", 0, Map.of(new BlockPos(10, 64, 10), "out of reach"));

        assertEquals(TaskStatus.SUCCESS, FellTreeTask.resultStatus(8));
        assertTrue(report.contains("felled 8 of 9"), report);
        assertTrue(report.contains("left 1 out of reach at 10 64 10"), report);
    }

    @Test
    void aTreeThatGaveNothingIsAFailure() {
        assertEquals(TaskStatus.FAILED, FellTreeTask.resultStatus(0));
    }

    @Test
    void aProtectedLogIsNeverCalledUnreachable() {
        String report = FellTreeTask.report(3, 4, "oak", 0, Map.of(new BlockPos(6, 5, 6), "protected by the server"));

        assertTrue(report.contains("left 1 protected by the server at 6 5 6"), report);
        assertFalse(report.contains("unreachable"), report);
    }

    @Test
    void aSharedCauseIsNamedOnceForEveryListedLog() {
        Map<BlockPos, String> left = new LinkedHashMap<>();
        left.put(new BlockPos(1, 2, 3), "protected by the server");
        left.put(new BlockPos(4, 5, 6), "protected by the server");

        String report = FellTreeTask.report(1, 3, "oak", 0, left);

        assertTrue(report.contains("left 2 protected by the server at 1 2 3, 4 5 6"), report);
    }

    @Test
    void mixedCausesNameTheFirstListedCellAndBoundTheirCoordinates() {
        Map<BlockPos, String> left = new LinkedHashMap<>();
        left.put(new BlockPos(1, 2, 3), "protected by the server");
        left.put(new BlockPos(4, 5, 6), "out of reach");
        left.put(new BlockPos(7, 8, 9), "out of reach");
        left.put(new BlockPos(10, 11, 12), "out of reach");
        left.put(new BlockPos(13, 14, 15), "out of reach");

        String report = FellTreeTask.report(2, 9, "oak", 1, left);

        assertTrue(report.contains("; skipped 1 that was not there when reached"), report);
        assertTrue(report.contains("; left 5 protected by the server at 1 2 3, 4 5 6, 7 8 9 and 2 more"), report);
        assertFalse(report.contains("10 11 12"), report);
    }

    @Test
    void aTreeWithNoShortfallCarriesNoLeftClause() {
        String report = FellTreeTask.report(8, 8, "oak", 0, Map.of());

        assertTrue(report.contains("felled 8 of 8 oak logs"), report);
        assertFalse(report.contains("left "), report);
    }
}
