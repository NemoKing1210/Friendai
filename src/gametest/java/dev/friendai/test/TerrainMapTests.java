package dev.friendai.test;

import dev.friendai.entity.CompanionManager;
import dev.friendai.features.cartography.TerrainCell;
import dev.friendai.features.cartography.TerrainGrid;
import dev.friendai.features.cartography.minecraft.TerrainSamplerService;
import dev.friendai.server.runtime.ServerRuntime;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

/** The terrain sampler records only already-loaded columns and stays inside its per-tick budget. */
public class TerrainMapTests {
    @GameTest(maxTicks = 400)
    public void samplerRecordsLoadedColumnsWithinBudget(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        ServerRuntime runtime = GameTestSupport.runtime(h.getLevel().getServer());
        CompanionManager.get(h.getLevel().getServer()).summon(owner);
        UUID id = owner.getUUID();

        h.onEachTick(() -> runtime.terrainSampler().tick(runtime));
        h.runAfterDelay(140, () -> {
            TerrainGrid grid = runtime.terrainSampler().grid(id);
            h.assertTrue(!grid.cells().isEmpty(), "the sampler must record at least one loaded column");
            int windows = 140 / TerrainSamplerService.SAMPLE_EVERY_TICKS + 1;
            int limit = TerrainSamplerService.MAX_CELLS_PER_TICK * windows;
            h.assertTrue(grid.size() <= limit, "bounded work: " + grid.size() + " <= " + limit);
            for (TerrainCell cell : grid.cells()) {
                h.assertTrue(
                        h.getLevel()
                                .hasChunk(
                                        SectionPos.blockToSectionCoord(
                                                cell.key().x()),
                                        SectionPos.blockToSectionCoord(
                                                cell.key().z())),
                        "a sampled column must be loaded: " + cell.key());
            }
            h.assertFalse(
                    grid.known(new TerrainCell.CellKey("minecraft:overworld", 10_000, 10_000)),
                    "an unobserved column stays unknown");
            h.succeed();
        });
    }
}
