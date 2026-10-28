package dev.friendai.test;

import dev.friendai.core.FriendaiServerState;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.features.crafting.Station;
import dev.friendai.features.knowledge.ResourceObservation;
import dev.friendai.features.navigation.pathing.Pos;
import dev.friendai.perception.resource.ObservationScheduler;
import dev.friendai.perception.resource.ResourceObserver;
import dev.friendai.perception.resource.ResourceSight;
import dev.friendai.task.obtain.WorldScan;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

public class ResourceObservationTests {
    @GameTest(maxTicks = 80)
    public void exposedOreObserved(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 5);
        BlockPos ore = h.absolutePos(new BlockPos(7, 3, 5));
        h.setBlock(7, 3, 5, Blocks.COAL_ORE);
        ResourceObserver observer = new ResourceObserver(companion);
        observer.inspect(ore);
        List<ResourceObservation> seen = drain(observer);
        h.assertTrue(
                seen.stream().anyMatch(item -> item.blockId().equals("minecraft:coal_ore")),
                "exposed coal was not observed: " + seen);
        h.succeed();
    }

    @GameTest(maxTicks = 40)
    public void sealedCaveOreUnknown(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 5);
        BlockPos ore = new BlockPos(7, 3, 5);
        h.setBlock(7, 3, 5, Blocks.COAL_ORE);
        for (int x = 6; x <= 8; x++)
            for (int y = 2; y <= 4; y++)
                for (int z = 4; z <= 6; z++) {
                    if (x == 7 && y == 3 && z == 5) continue;
                    h.setBlock(x, y, z, Blocks.STONE);
                }
        ResourceObserver observer = new ResourceObserver(companion);
        observer.inspect(h.absolutePos(ore));
        List<ResourceObservation> seen = drain(observer);
        h.assertTrue(
                seen.stream().noneMatch(item -> item.location().pos().equals(pos(ore))),
                "sealed ore leaked into observations: " + seen);
        h.succeed();
    }

    @GameTest(maxTicks = 60)
    public void newFaceLearnedOnlyAfterBreak(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 5);
        BlockPos ore = new BlockPos(7, 3, 5);
        h.setBlock(7, 3, 5, Blocks.COAL_ORE);
        h.setBlock(6, 3, 5, Blocks.STONE);
        h.setBlock(7, 2, 5, Blocks.STONE);
        h.setBlock(7, 4, 5, Blocks.STONE);
        h.setBlock(7, 3, 4, Blocks.STONE);
        h.setBlock(7, 3, 6, Blocks.STONE);
        ResourceObserver observer = new ResourceObserver(companion);
        observer.inspect(h.absolutePos(ore));
        h.assertTrue(drain(observer).isEmpty(), "ore should remain unknown behind the stone face");
        BlockPos wall = h.absolutePos(new BlockPos(6, 3, 5));
        h.getLevel().destroyBlock(wall, false);
        observer.changed(wall);
        h.assertTrue(
                drain(observer).stream().anyMatch(item -> item.blockId().equals("minecraft:coal_ore")),
                "exposed face was not inspected after break");
        h.succeed();
    }

    @GameTest(maxTicks = 60)
    public void glassVisibleTintedGlassOccluded(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 5);
        BlockPos ore = new BlockPos(10, 3, 5);
        h.setBlock(10, 3, 5, Blocks.COAL_ORE);
        h.setBlock(5, 3, 5, Blocks.GLASS);
        ResourceObserver clearObserver = new ResourceObserver(companion);
        clearObserver.inspect(h.absolutePos(ore));
        h.assertTrue(
                drain(clearObserver).stream().anyMatch(item -> item.blockId().equals("minecraft:coal_ore")),
                "clear glass should transmit sight");
        h.setBlock(5, 3, 5, Blocks.TINTED_GLASS);
        ResourceObserver tintedObserver = new ResourceObserver(companion);
        tintedObserver.inspect(h.absolutePos(ore));
        h.assertTrue(
                drain(tintedObserver).stream().noneMatch(item -> item.blockId().equals("minecraft:coal_ore")),
                "tinted glass should occlude sight");
        h.succeed();
    }

    @GameTest(maxTicks = 40)
    public void partialShapeOccludesOnlyIntersectedSamples(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 5);
        BlockPos slab = h.absolutePos(new BlockPos(5, 3, 5));
        BlockPos ore = h.absolutePos(new BlockPos(10, 3, 5));
        h.setBlock(5, 3, 5, Blocks.STONE_SLAB);
        h.setBlock(10, 3, 5, Blocks.COAL_ORE);
        Vec3 lowFrom = companion.getEyePosition().add(0, -0.35, 0);
        Vec3 highFrom = companion.getEyePosition().add(0, 0.35, 0);
        ResourceSight.Job blocked = ResourceSight.rayTo(h.getLevel(), lowFrom, Vec3.atCenterOf(ore), ore);
        ResourceSight.Job clear = ResourceSight.rayTo(h.getLevel(), highFrom, Vec3.atCenterOf(ore), ore);
        h.assertTrue(
                run(blocked) != ResourceSight.Outcome.VISIBLE,
                "ray through the slab's occupied half should be blocked");
        h.assertTrue(
                run(clear) == ResourceSight.Outcome.VISIBLE, "ray through the slab's empty half should reach the ore");
        h.succeed();
    }

    @GameTest(maxTicks = 20)
    public void unloadedRayDoesNotLoadChunk(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        BlockPos unloaded = h.absolutePos(new BlockPos(256, 3, 256));
        boolean loadedBefore = h.getLevel().hasChunkAt(unloaded);
        ResourceSight.Job job =
                ResourceSight.rayTo(h.getLevel(), companion.getEyePosition(), Vec3.atCenterOf(unloaded), unloaded);
        h.assertTrue(run(job) == ResourceSight.Outcome.UNLOADED, "ray did not stop at unloaded terrain");
        h.assertTrue(!loadedBefore && !h.getLevel().hasChunkAt(unloaded), "observation loaded an unloaded chunk");
        h.succeed();
    }

    @GameTest(maxTicks = 30)
    public void hiddenResourceAbsentFromWorldFactsAndStations(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        var owner = GameTestSupport.mockPlayerAt(h, 2, 2, 3);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 5);
        companion.setOwner(owner.getUUID());
        BlockPos ore = new BlockPos(7, 3, 5);
        h.setBlock(7, 3, 5, Blocks.COAL_ORE);
        h.setBlock(8, 3, 5, Blocks.FURNACE);
        h.setBlock(
                7,
                2,
                5,
                BuiltInRegistries.BLOCK
                        .getValue(Identifier.parse("minecraft:red_bed"))
                        .defaultBlockState());
        h.setBlock(7, 4, 5, Blocks.BEEHIVE);
        h.setBlock(8, 2, 5, Blocks.OAK_LOG);
        for (int x = 6; x <= 9; x++)
            for (int y = 2; y <= 4; y++)
                for (int z = 4; z <= 6; z++) {
                    if (x == 7 && y == 3 && z == 5
                            || x == 8 && y == 3 && z == 5
                            || x == 7 && y == 2 && z == 5
                            || x == 7 && y == 4 && z == 5
                            || x == 8 && y == 2 && z == 5) continue;
                    h.setBlock(x, y, z, Blocks.STONE);
                }
        var resources = FriendaiServerState.get(h.getLevel().getServer()).resources();
        var facts = WorldScan.scan(h.getLevel(), companion, 16, true, false);
        h.assertTrue(!facts.blocksNearby().containsKey("minecraft:coal_ore"), "hidden ore entered WorldFacts");
        h.assertTrue(
                !facts.stationsNearby().contains(Station.FURNACE.noun()), "hidden furnace entered available stations");
        h.assertTrue(facts.containerItems().isEmpty(), "unopened container contents entered WorldFacts");
        var beds =
                dev.friendai.task.mining.BlockMatcher.parse("#minecraft:beds").orElseThrow();
        var hives = dev.friendai.task.mining.BlockMatcher.parse("#minecraft:beehives")
                .orElseThrow();
        var logs =
                dev.friendai.task.mining.BlockMatcher.parse("#minecraft:logs").orElseThrow();
        h.assertTrue(resources.knownOrVisible(companion, beds, 24, 4).isEmpty(), "hidden bed was selected");
        h.assertTrue(resources.visible(companion, hives, 24, 4).isEmpty(), "hidden hive was selected");
        h.assertTrue(resources.visibleBlockIds(companion, 24).isEmpty(), "hidden tree was offered");
        h.assertTrue(resources.knownOrVisible(companion, logs, 24, 4).isEmpty(), "hidden log entered tree candidates");
        h.assertTrue(
                resources.rememberedBlockId(companion, h.absolutePos(ore)).isEmpty(),
                "unseen coordinate was remembered");
        h.succeed();
    }

    @GameTest(maxTicks = 200)
    public void followingLearnsCoalWithoutBrainTurn(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        var owner = GameTestSupport.mockPlayerAt(h, 2, 2, 3);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 5);
        companion.setOwner(owner.getUUID());
        companion.setNoGravity(true);
        companion.executionCoordinator().start(new dev.friendai.task.builtin.StayTask(), "stay");
        h.setBlock(5, 3, 5, Blocks.COAL_ORE);
        var state = FriendaiServerState.get(h.getLevel().getServer());
        h.assertTrue(state.sessions().brainOf(owner.getUUID()).isEmpty(), "test must start without a brain turn");
        CompanionManager.get(h.getLevel().getServer()).register(companion);
        var scheduler = new ObservationScheduler(state.resources()::observe);
        scheduler.register(companion);
        h.succeedWhen(() -> {
            scheduler.tick(
                    h.getLevel().getServer(),
                    h.getLevel().getServer().overworld().getGameTime());
            BlockPos origin = companion.blockPosition();
            var query = new dev.friendai.features.knowledge.ResourceQuery.Request(
                    java.util.Set.of("minecraft:coal_ore"),
                    h.getLevel().dimension().identifier().toString(),
                    pos(origin),
                    16,
                    20,
                    true);
            var book = state.knowledge().book(owner.getUUID());
            var known = dev.friendai.features.knowledge.ResourceQuery.find(book, query);
            h.assertFalse(known.isEmpty(), "waiting for ordinary observation to record exposed coal");
            h.assertTrue(
                    state.sessions().brainOf(owner.getUUID()).isEmpty(),
                    "passive observation must not start a brain turn");
            h.succeed();
        });
    }

    @GameTest(maxTicks = 80)
    public void ownerlessCompanionRetainsOnlyItsCurrentSightEvidence(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 5);
        for (int x = 5; x <= 7; x++)
            for (int y = 2; y <= 5; y++)
                for (int z = 1; z <= 10; z++) {
                    h.setBlock(x, y, z, Blocks.COAL_ORE);
                }
        var state = FriendaiServerState.get(h.getLevel().getServer());
        BlockPos target = h.absolutePos(new BlockPos(5, 3, 5));
        var ray = ResourceSight.rayTo(h.getLevel(), companion.getEyePosition(), Vec3.atCenterOf(target), target);
        ResourceSight.Outcome outcome;
        do {
            outcome = ray.step(128);
        } while (outcome == ResourceSight.Outcome.PENDING);
        h.assertTrue(outcome == ResourceSight.Outcome.VISIBLE, "fixture target must pass the direct sight ray");
        BlockPos viewpoint = companion.blockPosition();
        String blockId = BuiltInRegistries.BLOCK
                .getKey(h.getLevel().getBlockState(target).getBlock())
                .toString();
        state.resources()
                .observe(
                        companion,
                        new ResourceObservation(
                                blockId,
                                new ResourceObservation.Location(
                                        h.getLevel().dimension().identifier().toString(),
                                        new Pos(target.getX(), target.getY(), target.getZ())),
                                new Pos(viewpoint.getX(), viewpoint.getY(), viewpoint.getZ()),
                                h.getLevel().getServer().overworld().getGameTime()));
        var visible = state.resources().currentVisible(companion, 16);
        h.assertTrue(
                visible.stream().anyMatch(observation -> observation.blockId().equals("minecraft:coal_ore")),
                "an ownerless companion should retain direct current sight evidence");
        h.assertTrue(companion.ownerUuid() == null, "the test companion must not acquire persistent owner scope");
        h.succeed();
    }

    @GameTest(maxTicks = 200)
    public void schedulerObservesLoadedOwnerlessCompanion(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 5);
        companion.setNoGravity(true);
        companion.executionCoordinator().start(new dev.friendai.task.builtin.StayTask(), "stay");
        h.setBlock(5, 3, 5, Blocks.COAL_ORE);
        BlockPos target = h.absolutePos(new BlockPos(5, 3, 5));
        var state = FriendaiServerState.get(h.getLevel().getServer());
        var scheduler = new ObservationScheduler(state.resources()::observe);
        scheduler.register(companion);
        scheduler.observer(companion).inspect(target);
        h.succeedWhen(() -> {
            scheduler.tick(
                    h.getLevel().getServer(),
                    h.getLevel().getServer().overworld().getGameTime());
            var observer = scheduler.observer(companion);
            boolean observed = state.resources().currentVisible(companion, 16).stream()
                    .anyMatch(observation -> observation.blockId().equals("minecraft:coal_ore"));
            h.assertTrue(
                    observed,
                    "scheduler should observe a loaded ownerless companion without persisting its sight; queued="
                            + observer.queuedJobs() + ", visible="
                            + state.resources().currentVisible(companion, 16));
            h.assertTrue(companion.ownerUuid() == null, "observer scheduling must not assign an owner");
            h.succeed();
        });
    }

    @GameTest(maxTicks = 80)
    public void directSightRecordsAnOpenDescendingCaveEntrance(GameTestHelper h) {
        GameTestSupport.keepLoaded(h, -2, -2, 20, 20);
        var owner = GameTestSupport.mockPlayerAt(h, 7, 14, 8);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 7, 14, 8);
        companion.setOwner(owner.getUUID());
        companion.setNoGravity(true);
        companion.executionCoordinator().start(new dev.friendai.task.builtin.StayTask(), "stay");
        for (int x = 0; x < 17; x++)
            for (int z = 0; z < 17; z++) {
                h.setBlock(x, 10, z, Blocks.STONE);
                for (int y = 11; y <= 17; y++) h.setBlock(x, y, z, Blocks.AIR);
            }
        h.setBlock(8, 10, 8, Blocks.AIR);
        for (int y = 7; y <= 9; y++) h.setBlock(8, y, 8, Blocks.AIR);
        h.setBlock(8, 6, 8, Blocks.STONE);
        BlockPos wall = h.absolutePos(new BlockPos(8, 6, 8));
        var direct = ResourceSight.rayTo(
                h.getLevel(), companion.getEyePosition(), net.minecraft.world.phys.Vec3.atCenterOf(wall), wall);
        ResourceSight.Outcome directOutcome;
        do {
            directOutcome = direct.step(128);
        } while (directOutcome == ResourceSight.Outcome.PENDING);
        h.assertTrue(
                directOutcome == ResourceSight.Outcome.VISIBLE && direct.unobstructedOpenCorridor(),
                "fixture must produce an open direct ray; outcome=" + directOutcome + ", cells="
                        + direct.openAirCells());
        var eyeCell = BlockPos.containing(companion.getEyePosition());
        h.assertTrue(
                dev.friendai.perception.resource.CaveEntranceDetector.detect(
                                direct.openAirCells().stream()
                                        .map(pos -> new Pos(pos.getX(), pos.getY(), pos.getZ()))
                                        .toList(),
                                new Pos(eyeCell.getX(), eyeCell.getY(), eyeCell.getZ()),
                                new Pos(wall.getX(), wall.getY(), wall.getZ()))
                        .isPresent(),
                "fixture ray must pass through the detector's opening geometry; cells=" + direct.openAirCells()
                        + ", eye=" + eyeCell + ", hit=" + direct.hitBlock());
        var state = FriendaiServerState.get(h.getLevel().getServer());
        var scheduler = new dev.friendai.perception.resource.ObservationScheduler(
                state.resources()::observe, state.resources()::observeCaveEntrance);
        scheduler.register(companion);
        scheduler.observer(companion).inspect(wall);
        h.succeedWhen(() -> {
            scheduler.tick(
                    h.getLevel().getServer(),
                    h.getLevel().getServer().overworld().getGameTime());
            var entrances = state.resources().observedCaveEntrances(companion);
            h.assertTrue(
                    !entrances.isEmpty(),
                    "a clear sight ray through the open shaft should record its cave entrance; metrics="
                            + scheduler.lastMetrics() + ", queued="
                            + scheduler.observer(companion).queuedJobs()
                            + ", eye=" + companion.getEyePosition());
            var entrance = entrances.getFirst();
            h.assertTrue(
                    entrance.interior().y() < entrance.opening().y(),
                    "the recorded interior point must lie below the visible opening");
            h.succeed();
        });
    }

    @GameTest(maxTicks = 40)
    public void sealedUndergroundWallDoesNotCreateCaveEntranceEvidence(GameTestHelper h) {
        GameTestSupport.keepLoaded(h, -2, -2, 20, 20);
        var owner = GameTestSupport.mockPlayerAt(h, 7, 14, 8);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 7, 14, 8);
        companion.setOwner(owner.getUUID());
        companion.setNoGravity(true);
        companion.executionCoordinator().start(new dev.friendai.task.builtin.StayTask(), "stay");
        for (int x = 0; x < 17; x++)
            for (int z = 0; z < 17; z++) {
                h.setBlock(x, 10, z, Blocks.STONE);
                for (int y = 11; y <= 17; y++) h.setBlock(x, y, z, Blocks.AIR);
            }
        h.setBlock(8, 6, 8, Blocks.STONE);
        BlockPos wall = h.absolutePos(new BlockPos(8, 6, 8));
        var state = FriendaiServerState.get(h.getLevel().getServer());
        var scheduler = new dev.friendai.perception.resource.ObservationScheduler(
                state.resources()::observe, state.resources()::observeCaveEntrance);
        scheduler.register(companion);
        var observer = scheduler.observer(companion);
        observer.inspect(wall);
        var batch = observer.tick(32, 128, Long.MAX_VALUE);
        batch.caveEntrances().forEach(entrance -> state.resources().observeCaveEntrance(companion, entrance));

        h.assertTrue(
                state.resources().observedCaveEntrances(companion).isEmpty(),
                "an opaque sealed floor must prevent any cave entrance evidence");
        h.succeed();
    }

    @GameTest(maxTicks = 3700)
    public void manyCompanionsRespectGlobalObservationCaps(GameTestHelper h) {
        GameTestSupport.platform(h, 32);
        var server = h.getLevel().getServer();
        var state = FriendaiServerState.get(server);
        List<CompanionEntity> companions = new ArrayList<>();
        for (int x = 0; x < 4; x++)
            for (int z = 0; z < 4; z++) {
                CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2 + x * 3, 2, 2 + z * 3);
                companions.add(companion);
            }
        int[] profileSizes = {1, 4, 16};
        int[] profileIndex = {0};
        int[] ticks = {0};
        Map<java.util.UUID, Integer> completed = new java.util.HashMap<>();
        dev.friendai.perception.resource.ObservationScheduler[] scheduler = {
            schedulerFor(companions, profileSizes[0], completed)
        };
        BlockPos far = h.absolutePos(new BlockPos(4096, 20, 4096));
        h.assertFalse(h.getLevel().hasChunkAt(far), "the profiling sentinel chunk should start unloaded");
        long[] totals = new long[6];
        h.succeedWhen(() -> {
            int count = profileSizes[profileIndex[0]];
            scheduler[0].tick(server, server.overworld().getGameTime());
            var metrics = scheduler[0].lastMetrics();
            h.assertTrue(
                    metrics.companions() == count, "profile must include exactly " + count + " companions: " + metrics);
            h.assertTrue(metrics.completedRays() <= 256, "global ray cap exceeded: " + metrics);
            h.assertTrue(metrics.visitedCells() <= 128, "global cell cap exceeded: " + metrics);
            for (int rays : metrics.completedByCompanion().values())
                h.assertTrue(rays <= 32, "per-companion ray cap exceeded: " + metrics);
            for (CompanionEntity companion : companions.subList(0, count)) {
                h.assertTrue(
                        scheduler[0].observer(companion).queuedJobs() <= 512,
                        "companion observation queue exceeded its cap");
            }
            metrics.completedByCompanion().forEach((id, rays) -> completed.merge(id, rays, Integer::sum));
            h.assertTrue(
                    state.chunkLoader().expeditionHorizon().activeLeases() == 0,
                    "observation work must not request expedition chunks");
            h.assertFalse(h.getLevel().hasChunkAt(far), "observation loaded the distant sentinel chunk");
            totals[0] += metrics.scheduledRays();
            totals[1] += metrics.completedRays();
            totals[2] += metrics.visitedCells();
            totals[3] += metrics.elapsedNanos();
            totals[4] = Math.max(totals[4], metrics.elapsedNanos());
            totals[5] = Math.max(totals[5], metrics.queuedJobs());
            h.assertTrue(++ticks[0] >= 1_200, "profiling " + count + " companions through tick " + ticks[0]);
            for (var entry : completed.entrySet())
                h.assertTrue(
                        entry.getValue() > 0,
                        "continuously active companion received no completed ray in 1200 ticks: " + entry.getKey());
            System.out.println("Observation profile (" + count + " companions, 1200 ticks): scheduled=" + totals[0]
                    + ", completed=" + totals[1] + ", cells=" + totals[2]
                    + ", avg_tick_us=" + totals[3] / 1_200 / 1_000
                    + ", max_tick_us=" + totals[4] / 1_000 + ", max_queue=" + totals[5]);
            if (++profileIndex[0] < profileSizes.length) {
                completed.clear();
                ticks[0] = 0;
                java.util.Arrays.fill(totals, 0);
                scheduler[0] = schedulerFor(companions, profileSizes[profileIndex[0]], completed);
                h.assertTrue(false, "starting the next observation profile");
                return;
            }
            h.succeed();
        });
    }

    @GameTest(maxTicks = 80)
    public void observerHonoursConfiguredRadius(GameTestHelper h) {
        GameTestSupport.platform(h, 24);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 5);
        BlockPos target = h.absolutePos(new BlockPos(14, 3, 5));
        h.setBlock(14, 3, 5, Blocks.COAL_ORE);
        ResourceObserver narrow = new ResourceObserver(companion, () -> 8);
        narrow.inspect(target);
        h.assertTrue(drain(narrow).isEmpty(), "a target beyond the configured 8-block radius must not be inspected");
        ResourceObserver wide = new ResourceObserver(companion);
        wide.inspect(target);
        h.assertTrue(
                drain(wide).stream()
                        .anyMatch(observation -> observation.blockId().equals("minecraft:coal_ore")),
                "a target within the default 16-block radius should be inspected");
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void recentEvidenceSurvivesTicks(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 5);
        var state = FriendaiServerState.get(h.getLevel().getServer());
        ServerLevel level = h.getLevel();
        BlockPos target = h.absolutePos(new BlockPos(5, 3, 5));
        h.setBlock(5, 3, 5, Blocks.ACACIA_LOG);
        state.resources()
                .observe(
                        companion,
                        new ResourceObservation(
                                "minecraft:acacia_log",
                                new ResourceObservation.Location(
                                        level.dimension().identifier().toString(),
                                        new Pos(target.getX(), target.getY(), target.getZ())),
                                new Pos(2, 2, 5),
                                level.getServer().overworld().getGameTime()));
        h.assertTrue(
                state.resources().currentVisible(companion, 16).stream()
                        .anyMatch(observation -> observation.blockId().equals("minecraft:acacia_log")),
                "the strict current view should contain the fresh observation");
        h.runAfterDelay(20, () -> {
            h.assertTrue(
                    state.resources().recentVisible(companion, 16).stream()
                            .anyMatch(observation -> observation.blockId().equals("minecraft:acacia_log")),
                    "recent evidence should survive 20 ticks");
            h.assertTrue(
                    state.resources().currentVisible(companion, 16).isEmpty(),
                    "the strict current view should expire after 20 ticks");
            h.succeed();
        });
    }

    @GameTest(maxTicks = 80)
    public void replacedBlockDropsOutOfRecentEvidence(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 5);
        var state = FriendaiServerState.get(h.getLevel().getServer());
        ServerLevel level = h.getLevel();
        BlockPos target = h.absolutePos(new BlockPos(5, 3, 5));
        h.setBlock(5, 3, 5, Blocks.ACACIA_LOG);
        Pos position = new Pos(target.getX(), target.getY(), target.getZ());
        state.resources()
                .observe(
                        companion,
                        new ResourceObservation(
                                "minecraft:acacia_log",
                                new ResourceObservation.Location(
                                        level.dimension().identifier().toString(), position),
                                new Pos(2, 2, 5),
                                level.getServer().overworld().getGameTime()));
        h.assertTrue(
                state.resources().recentVisible(companion, 16).stream()
                        .anyMatch(observation -> observation.location().pos().equals(position)),
                "the fixture must be in the recent window");
        h.setBlock(5, 3, 5, Blocks.STONE);
        h.assertTrue(
                state.resources().confirmedRecent(companion, 16).stream()
                        .noneMatch(observation -> observation.location().pos().equals(position)),
                "a replaced block must not survive confirmed evidence");
        h.succeed();
    }

    private static dev.friendai.perception.resource.ObservationScheduler schedulerFor(
            List<CompanionEntity> companions, int count, Map<java.util.UUID, Integer> completed) {
        var scheduler = new dev.friendai.perception.resource.ObservationScheduler();
        for (CompanionEntity companion : companions.subList(0, count)) {
            scheduler.register(companion);
            completed.put(companion.getUUID(), 0);
        }
        return scheduler;
    }

    @GameTest(maxTicks = 20)
    public void ownSuccessfulBreakRemovesExactlyOneMember(GameTestHelper h) {
        GameTestSupport.platform(h, 10);
        var owner = GameTestSupport.mockPlayerAt(h, 2, 2, 3);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 5);
        companion.setOwner(owner.getUUID());
        var state = FriendaiServerState.get(h.getLevel().getServer());
        var book = state.knowledge().book(owner.getUUID());
        BlockPos first = h.absolutePos(new BlockPos(5, 3, 5));
        BlockPos second = h.absolutePos(new BlockPos(6, 3, 5));
        book.observe(observation(first, companion.blockPosition()), "minecraft:coal_ore");
        book.observe(observation(second, companion.blockPosition()), "minecraft:coal_ore");
        state.resources().ownBreak(companion, first);
        h.assertTrue(book.historicalBlockCount(1) == 1, "successful break should remove exactly one member");
        h.assertTrue(
                book.snapshot().records().stream().anyMatch(record -> record.x() == second.getX()),
                "neighboring member must remain remembered");
        h.succeed();
    }

    @GameTest(maxTicks = 20)
    public void dismissAndReloadRetainsKnowledge(GameTestHelper h) {
        GameTestSupport.platform(h, 10);
        var owner = GameTestSupport.mockPlayerAt(h, 2, 2, 3);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 5);
        companion.setOwner(owner.getUUID());
        CompanionManager.get(h.getLevel().getServer()).register(companion);
        var server = h.getLevel().getServer();
        var state = FriendaiServerState.get(server);
        BlockPos ore = h.absolutePos(new BlockPos(5, 3, 5));
        long siteId = state.knowledge()
                .book(owner.getUUID())
                .observe(observation(ore, companion.blockPosition()), "minecraft:coal_ore");
        state.knowledge().flushAll();
        h.assertTrue(CompanionManager.get(server).dismiss(owner), "test companion should be dismissed");
        FriendaiServerState.close(server);
        var reloaded = FriendaiServerState.get(server);
        h.assertTrue(
                reloaded.knowledge().book(owner.getUUID()).site(siteId, 2).isPresent(),
                "dismiss and server-state reload must retain the owner's site");
        h.succeed();
    }

    @GameTest(maxTicks = 20)
    public void dimensionChangeDropsPendingObservation(GameTestHelper h) {
        GameTestSupport.platform(h, 10);
        var owner = GameTestSupport.mockPlayerAt(h, 2, 2, 3);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 5);
        companion.setOwner(owner.getUUID());
        var server = h.getLevel().getServer();
        var state = FriendaiServerState.get(server);
        CompanionManager.get(server).register(companion);
        state.observations().register(companion);
        ResourceObserver previous = state.observations().observer(companion);
        previous.scheduleSweep(0);
        h.assertTrue(previous.queuedJobs() > 0, "pre-transfer sweep should be pending");
        ServerLevel destination = server.getLevel(Level.NETHER);
        BlockPos spawn = new BlockPos(0, 64, 0);
        var moved = companion.teleport(new TeleportTransition(
                destination, Vec3.atBottomCenterOf(spawn), Vec3.ZERO, 0.0F, 0.0F, TeleportTransition.DO_NOTHING));
        h.assertTrue(moved instanceof CompanionEntity, "companion should transfer to the second dimension");
        ResourceObserver next = state.observations().observer((CompanionEntity) moved);
        h.assertTrue(
                next != previous && next.queuedJobs() == 0, "pending observations must not cross a dimension transfer");
        h.succeed();
    }

    private static ResourceObservation observation(BlockPos block, BlockPos viewpoint) {
        return new ResourceObservation(
                "minecraft:coal_ore",
                new ResourceObservation.Location("minecraft:overworld", pos(block)),
                pos(viewpoint),
                1);
    }

    private static List<ResourceObservation> drain(ResourceObserver observer) {
        List<ResourceObservation> observations = new ArrayList<>();
        for (int i = 0; i < 64 && observer.queuedJobs() > 0; i++) {
            observations.addAll(observer.tick(256, 128, Long.MAX_VALUE).observations());
        }
        return observations;
    }

    private static ResourceSight.Outcome run(ResourceSight.Job job) {
        ResourceSight.Outcome outcome = ResourceSight.Outcome.PENDING;
        for (int i = 0; i < 128 && outcome == ResourceSight.Outcome.PENDING; i++) outcome = job.step(128);
        return outcome;
    }

    private static dev.friendai.features.navigation.pathing.Pos pos(BlockPos pos) {
        return new dev.friendai.features.navigation.pathing.Pos(pos.getX(), pos.getY(), pos.getZ());
    }
}
