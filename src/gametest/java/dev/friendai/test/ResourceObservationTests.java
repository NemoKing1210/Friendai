package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.perception.resource.ResourceObserver;
import dev.friendai.perception.resource.ResourceSight;
import dev.friendai.knowledge.ResourceObservation;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public class ResourceObservationTests {
    @GameTest(maxTicks = 40)
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
