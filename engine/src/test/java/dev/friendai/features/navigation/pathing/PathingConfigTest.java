package dev.friendai.features.navigation.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.foundation.spatial.PathingConfig;
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

    @Test
    void additionalBreaksExtendTheBlacklistAfterTheConfiguredEntries() {
        PathingConfig base =
                new PathingConfig(true, true, List.of("minecraft:obsidian"), List.of("minecraft:planks"), 7, 123, 9);

        PathingConfig wider = base.withAdditionalBreaks(List.of("#minecraft:logs", "minecraft:obsidian"));

        assertEquals(List.of("minecraft:obsidian", "#minecraft:logs"), wider.breakBlacklist());
        assertEquals(base.placeBlacklist(), wider.placeBlacklist());
        assertEquals(base.searchBudgetMs(), wider.searchBudgetMs());
        assertEquals(base.maxNodes(), wider.maxNodes());
        assertEquals(base.maxGatherBlocks(), wider.maxGatherBlocks());
        assertEquals(List.of("minecraft:obsidian"), base.breakBlacklist());
    }
}
