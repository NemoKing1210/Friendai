package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.entity.CompanionManager;
import dev.friendai.core.FriendaiServerState;
import dev.friendai.perception.resource.ResourceObserver;
import dev.friendai.perception.resource.ResourceSight;
import dev.friendai.knowledge.ResourceObservation;
import dev.friendai.pathing.Pos;
import dev.friendai.crafting.Station;
import dev.friendai.task.obtain.WorldScan;
import dev.friendai.task.crafting.Stations;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.gametest.framework.GameTestHelper;
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
        h.assertTrue(seen.stream().anyMatch(item -> item.blockId().equals("minecraft:coal_ore")), "exposed coal was not observed: " + seen);
        h.succeed();
    }

    @GameTest(maxTicks = 40)
    public void sealedCaveOreUnknown(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 5);
        BlockPos ore = new BlockPos(7, 3, 5);
        h.setBlock(7, 3, 5, Blocks.COAL_ORE);
        for (int x = 6; x <= 8; x++) for (int y = 2; y <= 4; y++) for (int z = 4; z <= 6; z++) {
            if (x == 7 && y == 3 && z == 5) continue;
            h.setBlock(x, y, z, Blocks.STONE);
        }
        ResourceObserver observer = new ResourceObserver(companion);
        observer.inspect(h.absolutePos(ore));
        List<ResourceObservation> seen = drain(observer);
        h.assertTrue(seen.stream().noneMatch(item -> item.location().pos().equals(pos(ore))), "sealed ore leaked into observations: " + seen);
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
        h.assertTrue(drain(observer).stream().anyMatch(item -> item.blockId().equals("minecraft:coal_ore")),
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
        h.assertTrue(drain(clearObserver).stream().anyMatch(item -> item.blockId().equals("minecraft:coal_ore")),
                "clear glass should transmit sight");
        h.setBlock(5, 3, 5, Blocks.TINTED_GLASS);
        ResourceObserver tintedObserver = new ResourceObserver(companion);
        tintedObserver.inspect(h.absolutePos(ore));
        h.assertTrue(drain(tintedObserver).stream().noneMatch(item -> item.blockId().equals("minecraft:coal_ore")),
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
        h.assertTrue(run(blocked) != ResourceSight.Outcome.VISIBLE, "ray through the slab's occupied half should be blocked");
        h.assertTrue(run(clear) == ResourceSight.Outcome.VISIBLE, "ray through the slab's empty half should reach the ore");
        h.succeed();
    }

    @GameTest(maxTicks = 20)
    public void unloadedRayDoesNotLoadChunk(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        BlockPos unloaded = new BlockPos(4096, 3, 4096);
        boolean loadedBefore = h.getLevel().hasChunkAt(unloaded);
        ResourceSight.Job job = ResourceSight.rayTo(h.getLevel(), companion.getEyePosition(), Vec3.atCenterOf(unloaded), unloaded);
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
        h.setBlock(7, 2, 5, BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:red_bed")).defaultBlockState());
        h.setBlock(7, 4, 5, Blocks.BEEHIVE);
        h.setBlock(8, 2, 5, Blocks.OAK_LOG);
        for (int x = 6; x <= 9; x++) for (int y = 2; y <= 4; y++) for (int z = 4; z <= 6; z++) {
            if (x == 7 && y == 3 && z == 5 || x == 8 && y == 3 && z == 5
                    || x == 7 && y == 2 && z == 5 || x == 7 && y == 4 && z == 5
                    || x == 8 && y == 2 && z == 5) continue;
            h.setBlock(x, y, z, Blocks.STONE);
        }
        var resources = FriendaiServerState.get(h.getLevel().getServer()).resources();
        var facts = WorldScan.scan(h.getLevel(), companion, 16, true, false);
        h.assertTrue(!facts.blocksNearby().containsKey("minecraft:coal_ore"), "hidden ore entered WorldFacts");
        h.assertTrue(!facts.stationsNearby().contains(Station.FURNACE.noun()), "hidden furnace entered available stations");
        h.assertTrue(facts.containerItems().isEmpty(), "unopened container contents entered WorldFacts");
        var beds = dev.friendai.task.mining.BlockMatcher.parse("#minecraft:beds").orElseThrow();
        var hives = dev.friendai.task.mining.BlockMatcher.parse("#minecraft:beehives").orElseThrow();
        var logs = dev.friendai.task.mining.BlockMatcher.parse("#minecraft:logs").orElseThrow();
        h.assertTrue(resources.knownOrVisible(companion, beds, 24, 4).isEmpty(), "hidden bed was selected");
        h.assertTrue(resources.visible(companion, hives, 24, 4).isEmpty(), "hidden hive was selected");
        h.assertTrue(resources.visibleBlockIds(companion, 24).isEmpty(), "hidden tree was offered");
        h.assertTrue(resources.knownOrVisible(companion, logs, 24, 4).isEmpty(), "hidden log entered tree candidates");
        h.assertTrue(resources.rememberedBlockId(companion, h.absolutePos(ore)).isEmpty(), "unseen coordinate was remembered");
        h.succeed();
    }

    @GameTest(maxTicks = 80)
    public void followingLearnsCoalWithoutBrainTurn(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        var owner = GameTestSupport.mockPlayerAt(h, 2, 2, 3);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 5);
        companion.setOwner(owner.getUUID());
        for (int x = 5; x <= 7; x++) for (int y = 2; y <= 5; y++) for (int z = 1; z <= 10; z++) {
            h.setBlock(x, y, z, Blocks.COAL_ORE);
        }
        var state = FriendaiServerState.get(h.getLevel().getServer());
        h.assertTrue(state.brains().brainOf(owner.getUUID()).isEmpty(), "test must start without a brain turn");
        CompanionManager.get(h.getLevel().getServer()).register(companion);
        state.observations().register(companion);
        h.runAfterDelay(60, () -> {
            BlockPos origin = companion.blockPosition();
            var query = new dev.friendai.knowledge.ResourceQuery.Request(java.util.Set.of("minecraft:coal_ore"),
                    h.getLevel().dimension().identifier().toString(), pos(origin), 16, 20, true);
            h.assertTrue(dev.friendai.knowledge.ResourceQuery.find(state.knowledge().book(owner.getUUID()), query).size() > 0,
                    "ordinary observation should record exposed coal");
            h.assertTrue(state.brains().brainOf(owner.getUUID()).isEmpty(), "passive observation must not start a brain turn");
            h.succeed();
        });
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
        h.assertTrue(book.snapshot().records().stream().anyMatch(record -> record.x() == second.getX()),
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
        long siteId = state.knowledge().book(owner.getUUID()).observe(observation(ore, companion.blockPosition()),
                "minecraft:coal_ore");
        state.knowledge().flushAll();
        h.assertTrue(CompanionManager.get(server).dismiss(owner), "test companion should be dismissed");
        FriendaiServerState.close(server);
        var reloaded = FriendaiServerState.get(server);
        h.assertTrue(reloaded.knowledge().book(owner.getUUID()).site(siteId, 2).isPresent(),
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
        var moved = companion.teleport(new TeleportTransition(destination, Vec3.atBottomCenterOf(spawn), Vec3.ZERO,
                0.0F, 0.0F, TeleportTransition.DO_NOTHING));
        h.assertTrue(moved instanceof CompanionEntity, "companion should transfer to the second dimension");
        ResourceObserver next = state.observations().observer((CompanionEntity) moved);
        h.assertTrue(next != previous && next.queuedJobs() == 0,
                "pending observations must not cross a dimension transfer");
        h.succeed();
    }

    private static ResourceObservation observation(BlockPos block, BlockPos viewpoint) {
        return new ResourceObservation("minecraft:coal_ore",
                new ResourceObservation.Location("minecraft:overworld", pos(block)), pos(viewpoint), 1);
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

    private static dev.friendai.pathing.Pos pos(BlockPos pos) {
        return new dev.friendai.pathing.Pos(pos.getX(), pos.getY(), pos.getZ());
    }
}
