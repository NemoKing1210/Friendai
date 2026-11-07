package dev.friendai.features.mining.minecraft.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.task.TaskStatus;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class FellTreeTaskTest {
    @Test
    void eightOfNineLogsIsASuccessWithTheShortfallNamed() {
        String report = FellTreeTask.report(8, 9, "oak", 0, List.of(new BlockPos(10, 64, 10)));

        assertEquals(TaskStatus.SUCCESS, FellTreeTask.resultStatus(8));
        assertTrue(report.contains("felled 8 of 9"), report);
        assertTrue(report.contains("left 1 unreachable at 10 64 10"), report);
    }

    @Test
    void aTreeThatGaveNothingIsAFailure() {
        assertEquals(TaskStatus.FAILED, FellTreeTask.resultStatus(0));
    }

    @Test
    void theShortfallNamesThreeCoordinatesAndCountsTheRest() {
        String report = FellTreeTask.report(
                2,
                9,
                "oak",
                1,
                List.of(
                        new BlockPos(1, 2, 3),
                        new BlockPos(4, 5, 6),
                        new BlockPos(7, 8, 9),
                        new BlockPos(10, 11, 12),
                        new BlockPos(13, 14, 15)));

        assertTrue(report.contains("; skipped 1 that was not there when reached"), report);
        assertTrue(report.contains("; left 5 unreachable at 1 2 3, 4 5 6, 7 8 9 and 2 more"), report);
        assertFalse(report.contains("10 11 12"), report);
    }

    @Test
    void aTreeWithNoShortfallCarriesNoUnreachableClause() {
        String report = FellTreeTask.report(8, 8, "oak", 0, List.of());

        assertTrue(report.contains("felled 8 of 8 oak logs"), report);
        assertFalse(report.contains("unreachable"), report);
    }
}
