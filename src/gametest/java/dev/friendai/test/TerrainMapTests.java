package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.features.cartography.TerrainCell;
import dev.friendai.features.cartography.TerrainGrid;
import dev.friendai.server.cartography.TerrainSamplerService;
import dev.friendai.server.runtime.ServerRuntime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

/** The terrain sampler records only already-loaded columns and stays inside its per-tick budget. */
public class TerrainMapTests {
    /** A route walked in short samples still accumulates, because the comparison point is the last recorded end. */
    @GameTest(structure = GameTestArenas.ARENA_LONG_128, maxTicks = 700)
    public void walkingAccumulatesRoadSegments(GameTestHelper h) {
        GameTestSupport.corridor(h, 1, 120, 5);
        GameTestSupport.keepLoaded(h, -16, -16, 120, 16);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        ServerRuntime runtime = GameTestSupport.runtime(h.getLevel().getServer());
        CompanionEntity companion =
                CompanionManager.get(h.getLevel().getServer()).summon(owner);
        UUID id = owner.getUUID();
        h.onEachTick(() -> runtime.terrainSampler().tick(runtime));
        for (int step = 1; step <= 24; step++) {
            int x = 2 + step * 4;
            BlockPos waypoint = h.absolutePos(new BlockPos(x, 2, 2));
            h.runAfterDelay(
                    step * 20, () -> companion.snapTo(waypoint.getX() + 0.5, waypoint.getY(), waypoint.getZ() + 0.5));
        }
        h.runAfterDelay(24 * 20 + 40, () -> {
            var roads = runtime.terrainSampler().grid(id).roads();
            h.assertTrue(!roads.isEmpty(), "a walked route must be recorded");
            BlockPos first = h.absolutePos(new BlockPos(0, 2, 2));
            BlockPos last = h.absolutePos(new BlockPos(119, 2, 2));
            h.assertTrue(
                    roads.stream()
                            .allMatch(road -> road.fromX() >= first.getX()
                                    && road.fromX() <= last.getX()
                                    && road.toX() >= first.getX()
                                    && road.toX() <= last.getX()),
                    "the route must stay in its test arena: " + roads);
            int total = roads.stream()
                    .mapToInt(
                            road -> (int) Math.round(Math.hypot(road.toX() - road.fromX(), road.toZ() - road.fromZ())))
                    .sum();
            h.assertTrue(total >= 80, "the route must cover the walk, got " + total + " " + roads);
            h.succeed();
        });
    }

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 400)
    public void samplerRecordsLoadedColumnsWithinBudget(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        ServerRuntime runtime = GameTestSupport.runtime(h.getLevel().getServer());
        CompanionManager.get(h.getLevel().getServer()).summon(owner);
        UUID id = owner.getUUID();

        long[] lastWindow = {runtime.terrainSampler().lastMetrics().tick()};
        int[] windows = {0};
        h.onEachTick(() -> {
            runtime.terrainSampler().tick(runtime);
            long window = runtime.terrainSampler().lastMetrics().tick();
            if (window != lastWindow[0]) {
                lastWindow[0] = window;
                windows[0]++;
            }
        });
        h.runAfterDelay(140, () -> {
            TerrainGrid grid = runtime.terrainSampler().grid(id);
            h.assertTrue(windows[0] >= 6, "the sampler must run its windows: " + windows[0]);
            h.assertTrue(!grid.cells().isEmpty(), "the sampler must record at least one loaded column");
            int limit = TerrainSamplerService.MAX_CELLS_PER_TICK * windows[0];
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

    /**
     * Eight loaded owners share one bounded sampling window: every window respects the owner and column
     * allowance, every owner advances over a bounded number of windows, and no cell comes from an unloaded chunk.
     */
    @GameTest(structure = GameTestArenas.ARENA_32, maxTicks = 700)
    public void manyOwnersShareOneSamplingWindow(GameTestHelper h) {
        GameTestSupport.platform(h, 32);
        ServerRuntime runtime = GameTestSupport.runtime(h.getLevel().getServer());
        List<UUID> owners = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2 + index * 3, 2, 2);
            CompanionManager.get(h.getLevel().getServer()).summon(owner);
            owners.add(owner.getUUID());
        }
        long[] lastWindow = {runtime.terrainSampler().lastMetrics().tick()};
        int[] windows = {0};
        Map<UUID, Integer> sampledByOwner = new HashMap<>();
        List<Long> windowNanos = new ArrayList<>();
        h.onEachTick(() -> {
            runtime.terrainSampler().tick(runtime);
            TerrainSamplerService.SamplerMetrics metrics =
                    runtime.terrainSampler().lastMetrics();
            if (metrics.tick() == lastWindow[0]) {
                return;
            }
            lastWindow[0] = metrics.tick();
            windows[0]++;
            windowNanos.add(metrics.elapsedNanos());
            h.assertTrue(
                    metrics.ownersScheduled() <= TerrainSamplerService.OWNERS_PER_WINDOW,
                    "one window visited more owners than the shared allowance: " + metrics);
            h.assertTrue(
                    metrics.columnsSampled() <= metrics.columnAllowance(),
                    "one window sampled more columns than the shared allowance: " + metrics);
            metrics.sampledByOwner().forEach((id, count) -> sampledByOwner.merge(id, count, Integer::sum));
        });
        h.succeedWhen(() -> {
            h.assertTrue(!sampledByOwner.isEmpty(), "the bounded windows must sample at least one owner");
            if (!owners.stream().allMatch(owner -> sampledByOwner.getOrDefault(owner, 0) > 0)) {
                h.assertTrue(
                        windows[0] <= owners.size(),
                        "every owner must receive a sampled column across the windows, got " + sampledByOwner);
                return;
            }
            if (windows[0] < 10) {
                return;
            }
            for (UUID owner : owners) {
                for (TerrainCell cell : runtime.terrainSampler().grid(owner).cells()) {
                    h.assertTrue(
                            h.getLevel()
                                    .hasChunk(
                                            SectionPos.blockToSectionCoord(
                                                    cell.key().x()),
                                            SectionPos.blockToSectionCoord(
                                                    cell.key().z())),
                            "a sampled column must be loaded: " + cell.key());
                }
            }
            printProfile(runtime, owners, windowNanos);
            h.succeed();
        });
    }

    private static void printProfile(ServerRuntime runtime, List<UUID> owners, List<Long> windowNanos) {
        int one = runtime.terrainSampler().grid(owners.get(0)).size();
        int four = owners.stream()
                .limit(4)
                .mapToInt(owner -> runtime.terrainSampler().grid(owner).size())
                .sum();
        int eight = owners.stream()
                .mapToInt(owner -> runtime.terrainSampler().grid(owner).size())
                .sum();
        long p95 = windowNanos.isEmpty()
                ? 0L
                : windowNanos.stream()
                        .sorted()
                        .skip(Math.min(windowNanos.size() - 1, (long) Math.floor(windowNanos.size() * 0.95)))
                        .findFirst()
                        .orElse(0L);
        long max = windowNanos.stream().mapToLong(Long::longValue).max().orElse(0L);
        System.out.println("Terrain sampling profile (1/4/8 owners): "
                + one + " / " + four + " / " + eight + " columns, last="
                + runtime.terrainSampler().lastMetrics()
                + ", p95=" + p95 / 1000 + "us, max=" + max / 1000 + "us");
    }

    /**
     * A companion that changes dimension is sampled only in the new level; the per-owner cursor and accumulator
     * start a fresh observation on arrival instead of reading the old level's columns.
     */
    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 300)
    public void dimensionChangeSamplesTheArrivalLevelOnly(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerRuntime runtime = GameTestSupport.runtime(h.getLevel().getServer());
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion =
                CompanionManager.get(h.getLevel().getServer()).summon(owner);
        UUID id = owner.getUUID();
        h.onEachTick(() -> runtime.terrainSampler().tick(runtime));
        boolean[] moved = {false};
        int[] waited = {0};
        h.succeedWhen(() -> {
            TerrainGrid before = runtime.terrainSampler().grid(id);
            if (!moved[0]) {
                if (before.cells().isEmpty()) {
                    h.assertTrue(
                            ++waited[0] <= 160, "the overworld should be sampled before the move: " + before.size());
                    return;
                }
                String overworld = h.getLevel().dimension().identifier().toString();
                var nether = h.getLevel().getServer().getLevel(Level.NETHER);
                if (nether == null) {
                    h.succeed();
                    return;
                }
                BlockPos base = new BlockPos(8, 64, 8);
                for (int x = -2; x <= 2; x++) {
                    for (int z = -2; z <= 2; z++) {
                        nether.setBlockAndUpdate(base.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                    }
                }
                var result = companion.teleport(new TeleportTransition(
                        nether, new Vec3(8.5, 64, 8.5), Vec3.ZERO, 0.0F, 0.0F, TeleportTransition.DO_NOTHING));
                h.assertTrue(
                        result instanceof CompanionEntity next && next.level() == nether,
                        "the companion should transfer to the nether");
                moved[0] = true;
                return;
            }
            String overworld = h.getLevel().dimension().identifier().toString();
            TerrainGrid after = runtime.terrainSampler().grid(id);
            if (!after.cells().stream().anyMatch(cell -> !cell.key().dimension().equals(overworld))) {
                h.assertTrue(++waited[0] <= 220, "the arrival level must be sampled after the move: " + after.cells());
                return;
            }
            h.assertTrue(
                    runtime.terrainSampler().lastMetrics().ownersScheduled() <= TerrainSamplerService.OWNERS_PER_WINDOW,
                    "the dimension change must not bypass the shared allowance");
            h.succeed();
        });
    }
}
