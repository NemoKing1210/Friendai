package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.features.construction.minecraft.task.ClearAreaTask;
import dev.friendai.server.execution.ExecutionCoordinator;
import dev.friendai.server.execution.TaskEventSink;
import dev.friendai.task.TaskStatus;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;

public class ClearTests {
    private static final class Result implements TaskEventSink {
        TaskStatus status;
        String summary;
        final List<String> notices = new ArrayList<>();

        @Override
        public void onFinished(int id, String toolName, TaskStatus status, String summary) {
            this.status = status;
            this.summary = summary;
        }

        @Override
        public void onCancelled(int id, String toolName, String reason) {}

        @Override
        public void onNotice(String text) {
            notices.add(text);
        }
    }

    private static ExecutionCoordinator runner(CompanionEntity companion, Result result) {
        ExecutionCoordinator runner = new ExecutionCoordinator(companion, () -> 300, () -> false);
        runner.setEventSink(result);
        return runner;
    }

    private static ClearAreaTask clear(GameTestHelper h, int x, int y, int z, boolean blocks) {
        return new ClearAreaTask(h.absolutePos(new BlockPos(x, y, z)), 4, 0, 1, true, blocks);
    }

    @GameTest(maxTicks = 600)
    public void clearsVegetationAndSnow(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        h.setBlock(3, 2, 2, Blocks.SHORT_GRASS);
        h.setBlock(4, 2, 3, Blocks.SHORT_GRASS);
        h.setBlock(2, 2, 4, Blocks.SNOW);
        Result result = new Result();
        ExecutionCoordinator runner = runner(companion, result);
        runner.start(clear(h, 2, 2, 2, false), "clear_area");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + ": " + result.summary);
            h.assertTrue(
                    h.getBlockState(new BlockPos(3, 2, 2)).isAir(),
                    "grass at 3,2,2: " + h.getBlockState(new BlockPos(3, 2, 2)));
            h.assertTrue(
                    h.getBlockState(new BlockPos(4, 2, 3)).isAir(),
                    "grass at 4,2,3: " + h.getBlockState(new BlockPos(4, 2, 3)));
            h.assertTrue(
                    h.getBlockState(new BlockPos(2, 2, 4)).isAir(),
                    "snow at 2,2,4: " + h.getBlockState(new BlockPos(2, 2, 4)));
            h.assertTrue(result.summary.contains("vegetation"), result.summary);
            h.assertTrue(result.summary.contains("snow"), result.summary);
        });
    }

    @GameTest(maxTicks = 600)
    public void aProtectedCellIsLeftAndReported(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        BlockPos target = h.absolutePos(new BlockPos(3, 2, 2));
        h.setBlock(3, 2, 2, Blocks.OAK_PLANKS);
        companion.hands().overrideProtection(pos -> pos.equals(target));
        Result result = new Result();
        ExecutionCoordinator runner = runner(companion, result);
        runner.start(clear(h, 2, 2, 2, true), "clear_area");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(result.status == TaskStatus.FAILED, "status " + result.status + ": " + result.summary);
            h.assertTrue(h.getBlockState(new BlockPos(3, 2, 2)).is(Blocks.OAK_PLANKS), "the protected block must stay");
            h.assertTrue(result.summary.contains("protected"), result.summary);
        });
    }

    @GameTest(maxTicks = 600)
    public void takesAWaterSourceWithABucket(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        h.setBlock(3, 2, 3, Blocks.WATER);
        companion.inventory().addItem(new ItemStack(Items.BUCKET, 1));
        Result result = new Result();
        ExecutionCoordinator runner = runner(companion, result);
        runner.start(clear(h, 2, 2, 2, false), "clear_area");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + ": " + result.summary);
            h.assertTrue(
                    h.getBlockState(new BlockPos(3, 2, 3)).isAir(),
                    "water at 3,2,3: " + h.getBlockState(new BlockPos(3, 2, 3)));
            h.assertTrue(companion.inventory().countItem(Items.WATER_BUCKET) == 1, "a water bucket must come back");
            h.assertTrue(result.summary.contains("water"), result.summary);
        });
    }

    @GameTest(maxTicks = 600)
    public void aWaterSourceIsTakenAfterWalkingToIt(GameTestHelper h) {
        GameTestSupport.platform(h, 32);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        h.setBlock(10, 2, 2, Blocks.WATER);
        companion.inventory().addItem(new ItemStack(Items.BUCKET, 1));
        Result result = new Result();
        ExecutionCoordinator runner = runner(companion, result);
        runner.start(new ClearAreaTask(companion.blockPosition(), 8, 0, 1, true, false), "clear_area");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + ": " + result.summary);
            h.assertTrue(
                    h.getBlockState(new BlockPos(10, 2, 2)).isAir(),
                    "water at 10,2,2: " + h.getBlockState(new BlockPos(10, 2, 2)));
            h.assertTrue(companion.inventory().countItem(Items.WATER_BUCKET) == 1, "a water bucket must come back");
            h.assertTrue(result.summary.contains("water"), result.summary);
        });
    }

    @GameTest(maxTicks = 600)
    public void waterWithoutABucketIsLeftAndReported(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        h.setBlock(3, 2, 3, Blocks.WATER);
        Result result = new Result();
        ExecutionCoordinator runner = runner(companion, result);
        runner.start(clear(h, 2, 2, 2, false), "clear_area");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(result.status == TaskStatus.FAILED, "status " + result.status + ": " + result.summary);
            h.assertTrue(
                    h.getBlockState(new BlockPos(3, 2, 3)).is(Blocks.WATER), "the water must stay without a bucket");
            h.assertTrue(result.summary.contains("no empty bucket"), result.summary);
        });
    }

    @GameTest(maxTicks = 600)
    public void aBlockEntityIsNeverCleared(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        h.setBlock(3, 2, 2, Blocks.SPAWNER);
        Result result = new Result();
        ExecutionCoordinator runner = runner(companion, result);
        runner.start(clear(h, 2, 2, 2, true), "clear_area");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(result.status == TaskStatus.FAILED, "status " + result.status + ": " + result.summary);
            h.assertTrue(h.getBlockState(new BlockPos(3, 2, 2)).is(Blocks.SPAWNER), "the block entity must stay");
            h.assertTrue(result.summary.contains("has contents"), result.summary);
        });
    }

    @GameTest(maxTicks = 600)
    public void breaksAStrayBlockOnlyWhenAsked(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        h.setBlock(3, 2, 2, Blocks.COBBLESTONE);
        Result result = new Result();
        ExecutionCoordinator runner = runner(companion, result);
        runner.start(clear(h, 2, 2, 2, true), "clear_area");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + ": " + result.summary);
            h.assertTrue(
                    h.getBlockState(new BlockPos(3, 2, 2)).isAir(),
                    "the stray block at 3,2,2: " + h.getBlockState(new BlockPos(3, 2, 2)));
            h.assertTrue(result.summary.contains("1 block"), result.summary);
        });
    }

    /** What the run breaks is picked up, not left on the ground to despawn. Dirt drops by hand. */
    @GameTest(maxTicks = 600)
    public void clearPicksUpWhatItBreaks(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        h.setBlock(3, 2, 2, Blocks.DIRT);
        Result result = new Result();
        ExecutionCoordinator runner = runner(companion, result);
        runner.start(clear(h, 2, 2, 2, true), "clear_area");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + ": " + result.summary);
            h.assertTrue(
                    h.getBlockState(new BlockPos(3, 2, 2)).isAir(),
                    "dirt at 3,2,2: " + h.getBlockState(new BlockPos(3, 2, 2)));
            h.assertTrue(
                    companion.inventory().countItem(Items.DIRT) == 1,
                    "the broken dirt must be in the inventory, found "
                            + companion.inventory().countItem(Items.DIRT));
        });
    }

    /**
     * Working from the outside in opens the inside of a dense canopy; starting at the top left the buried cells
     * "out of sight" and the task ran on with the cells it could not reach.
     */
    @GameTest(maxTicks = 2400)
    public void aDenseCanopyIsClearedWithoutStalling(GameTestHelper h) {
        GameTestSupport.platform(h, 64);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        var leaves = Blocks.BIRCH_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        for (int y = 2; y <= 9; y++) {
            for (int x = 5; x <= 9; x++) {
                for (int z = 5; z <= 9; z++) {
                    h.setBlock(x, y, z, leaves);
                }
            }
        }
        Result result = new Result();
        ExecutionCoordinator runner = runner(companion, result);
        runner.start(new ClearAreaTask(h.absolutePos(new BlockPos(7, 2, 7)), 4, 0, 8, false, false), "clear_area");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + ": " + result.summary);
            h.assertTrue(!result.summary.contains("out of sight"), result.summary);
        });
    }

    /**
     * An area asked too large must not hold the companion for an hour: the run stops at its total budget, reports the
     * cells it did not reach for another pass, and exposes a progress line while it works. Every cell costs at least
     * a tick to pick and a tick to break, so more cells than the budget can possibly reach leave work behind.
     */
    @GameTest(maxTicks = 400)
    public void aTickBudgetStopsTheRunAndReportsTheRest(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        List<BlockPos> grass = new ArrayList<>();
        for (int x = 0; x <= 5 && grass.size() < 24; x++) {
            for (int z = 0; z <= 5 && grass.size() < 24; z++) {
                BlockPos pos = new BlockPos(x, 2, z);
                if (!pos.equals(new BlockPos(2, 2, 2))) {
                    grass.add(pos);
                }
            }
        }
        for (BlockPos pos : grass) {
            // Short grass needs soil under it; on the test floor it would pop as soon as a neighbour changes, and the
            // run would finish early for the wrong reason.
            h.setBlock(pos.below(), Blocks.DIRT);
            h.setBlock(pos, Blocks.SHORT_GRASS);
        }
        Result result = new Result();
        ExecutionCoordinator runner = runner(companion, result);
        ClearAreaTask task = new ClearAreaTask(h.absolutePos(new BlockPos(2, 2, 2)), 4, 0, 1, true, false, 40);
        runner.start(task, "clear_area");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + ": " + result.summary);
            h.assertTrue(result.summary.contains("more cells left for another pass"), result.summary);
            int remaining = 0;
            for (BlockPos pos : grass) {
                if (h.getBlockState(pos).is(Blocks.SHORT_GRASS)) {
                    remaining++;
                }
            }
            h.assertTrue(remaining > 0, "the run cleared every cell despite its budget: " + result.summary);
            h.assertTrue(task.debugStatus().contains("/"), task.debugStatus());
        });
    }

    @GameTest(maxTicks = 200)
    public void lavaOnlyAreaSucceeds(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        h.setBlock(8, 2, 8, Blocks.LAVA);
        h.setBlock(7, 2, 8, Blocks.STONE);
        h.setBlock(9, 2, 8, Blocks.STONE);
        h.setBlock(8, 2, 7, Blocks.STONE);
        h.setBlock(8, 2, 9, Blocks.STONE);
        BlockPos center = h.absolutePos(new BlockPos(5, 2, 5));
        Result result = new Result();
        ExecutionCoordinator runner = runner(companion, result);
        runner.start(new ClearAreaTask(center, 4, 0, 1, true, false), "clear_area");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + ": " + result.summary);
            h.assertTrue(result.summary.contains("lava"), result.summary);
            h.assertTrue(h.getBlockState(new BlockPos(8, 2, 8)).is(Blocks.LAVA), "the lava must stay");
        });
    }

    /**
     * Reading one block of a distant box generates its chunk, so a scan that touches the world there turns a clear
     * order into minutes of terrain generation. The unloaded part is reported instead.
     */
    @GameTest(maxTicks = 100)
    public void anUnloadedAreaIsReportedAndNotGenerated(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        BlockPos center = h.absolutePos(new BlockPos(2, 2, 2)).offset(2000, 0, 0);
        BlockPos min = center.offset(-4, 0, -4);
        BlockPos max = center.offset(4, 0, 4);
        h.assertTrue(!anyChunkLoaded(h.getLevel(), min, max), "the test area should start unloaded");
        Result result = new Result();
        ExecutionCoordinator runner = runner(companion, result);
        runner.start(new ClearAreaTask(center, 4, 0, 1, true, false), "clear_area");
        // The generated chunks are unloaded again after a while, so a single late look cannot prove the scan kept
        // its hands off: remember a chunk the moment it is seen in memory.
        boolean[] generated = new boolean[1];
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "still running");
            generated[0] |= anyChunkLoaded(h.getLevel(), min, max);
            h.assertTrue(!generated[0], "the scan generated chunks of the unloaded area");
            h.assertTrue(result.status == TaskStatus.FAILED, "status " + result.status + ": " + result.summary);
            h.assertTrue(result.summary.contains("not loaded"), result.summary);
        });
    }

    /** Whether any chunk covering the box is in memory, asked without loading or generating one. */
    private static boolean anyChunkLoaded(ServerLevel level, BlockPos min, BlockPos max) {
        for (int cx = min.getX() >> 4; cx <= max.getX() >> 4; cx++) {
            for (int cz = min.getZ() >> 4; cz <= max.getZ() >> 4; cz++) {
                if (level.getChunkSource().getChunkNow(cx, cz) != null) {
                    return true;
                }
            }
        }
        return false;
    }

    @GameTest(maxTicks = 200)
    public void anEmptyPlotSucceeds(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        BlockPos center = h.absolutePos(new BlockPos(5, 2, 5));
        Result result = new Result();
        ExecutionCoordinator runner = runner(companion, result);
        runner.start(new ClearAreaTask(center, 4, 0, 1, true, false), "clear_area");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + ": " + result.summary);
            String expected = "nothing to clear around " + center.getX() + " " + center.getY() + " " + center.getZ();
            h.assertTrue(expected.equals(result.summary), "summary " + result.summary);
        });
    }
}
