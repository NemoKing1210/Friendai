package dev.friendai.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PathingConfigTest {
    @Test
    void withActionsKeepsTheOtherSettings() {
        PathingConfig base =
                new PathingConfig(true, true, List.of("minecraft:obsidian"), List.of("minecraft:planks"), 7, 123, 9);

        PathingConfig quiet = base.withActions(false, false);

        assertFalse(quiet.allowBreak());
        assertFalse(quiet.allowPlace());
        assertEquals(base.breakBlacklist(), quiet.breakBlacklist());
        assertEquals(base.placeBlacklist(), quiet.placeBlacklist());
        assertEquals(base.searchBudgetMs(), quiet.searchBudgetMs());
        assertEquals(base.maxNodes(), quiet.maxNodes());
        assertEquals(base.maxGatherBlocks(), quiet.maxGatherBlocks());
    }

    @Test
    void withActionsDoesNotChangeTheOriginal() {
        PathingConfig base = PathingConfig.DEFAULTS;
        base.withActions(false, false);
        assertTrue(base.allowBreak());
        assertTrue(base.allowPlace());
    }
}
