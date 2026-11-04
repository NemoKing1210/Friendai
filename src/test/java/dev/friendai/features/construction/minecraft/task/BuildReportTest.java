package dev.friendai.features.construction.minecraft.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.construction.blueprint.PlacementRefusal;
import dev.friendai.foundation.spatial.Pos;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The sentences a finished or failed build reports: counts, the remedy rule and the recovery suffix. */
class BuildReportTest {
    private static PlacementRefusal unreachable() {
        return new PlacementRefusal.OutOfReach(Pos.of(1, 2, 3), "no way in", 1);
    }

    @Test
    void aFinishedBuildNamesEveryCountAndTheSelectedParts() {
        String line = BuildReport.finished("hut", "10 64 -4", "north", 42, " (walls)", 3, 5, 0);

        assertEquals("built hut at 10 64 -4 facing north: 42 placed (walls), 3 cleared, 5 foundation", line);
    }

    @Test
    void theRecoverySuffixAppearsOnlyAfterASelfBlockedRecovery() {
        assertEquals("", BuildReport.recoverySuffix(0));
        assertEquals(", 2 self-blocked recoveries", BuildReport.recoverySuffix(2));

        assertTrue(
                BuildReport.finished("hut", "0 0 0", "south", 1, "", 0, 0, 1).endsWith(", 1 self-blocked recoveries"));
    }

    @Test
    void aSiteFailureNamesTheBareCauseAndAPlacementFailureAddsTheRemedy() {
        String site = BuildReport.failed(unreachable(), true, "", 0);
        String placement = BuildReport.failed(unreachable(), false, " resume here", 0);

        assertFalse(site.contains("resume here"), site);
        assertFalse(site.contains("; "), site);
        assertTrue(placement.contains("resume here"), placement);
        assertTrue(placement.contains("; "), placement);
    }

    @Test
    void severalRefusalsAreJoinedAndKeepTheResumeHint() {
        String line = BuildReport.failed(
                List.of(unreachable(), new PlacementRefusal.ForeignBlock(Pos.of(2, 2, 3), "minecraft:stone", 1)),
                " resume here",
                3);

        assertTrue(line.contains("; "), line);
        assertTrue(line.endsWith(" resume here, 3 self-blocked recoveries"), line);
    }
}
