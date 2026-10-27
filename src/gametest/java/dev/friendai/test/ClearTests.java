package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.task.TaskListener;
import dev.friendai.task.TaskRunner;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.builtin.ClearAreaTask;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

public class ClearTests {
    private static final class Result implements TaskListener {
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

    private static TaskRunner runner(CompanionEntity companion, Result result) {
        TaskRunner runner = new TaskRunner(companion, () -> 300, () -> false);
        runner.setListener(result);
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
        TaskRunner runner = runner(companion, result);
        runner.start(clear(h, 2, 2, 2, false), "clear_area");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + ": " + result.summary);
            h.assertTrue(h.getBlockState(new BlockPos(3, 2, 2)).isAir(),
                    "grass at 3,2,2: " + h.getBlockState(new BlockPos(3, 2, 2)));
            h.assertTrue(h.getBlockState(new BlockPos(4, 2, 3)).isAir(),
                    "grass at 4,2,3: " + h.getBlockState(new BlockPos(4, 2, 3)));
            h.assertTrue(h.getBlockState(new BlockPos(2, 2, 4)).isAir(),
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
        TaskRunner runner = runner(companion, result);
        runner.start(clear(h, 2, 2, 2, true), "clear_area");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(result.status == TaskStatus.FAILED, "status " + result.status + ": " + result.summary);
            h.assertTrue(h.getBlockState(new BlockPos(3, 2, 2)).is(Blocks.OAK_PLANKS),
                    "the protected block must stay");
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
        TaskRunner runner = runner(companion, result);
        runner.start(clear(h, 2, 2, 2, false), "clear_area");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + ": " + result.summary);
            h.assertTrue(h.getBlockState(new BlockPos(3, 2, 3)).isAir(),
                    "water at 3,2,3: " + h.getBlockState(new BlockPos(3, 2, 3)));
            h.assertTrue(companion.inventory().countItem(Items.WATER_BUCKET) == 1,
                    "a water bucket must come back");
            h.assertTrue(result.summary.contains("water"), result.summary);
        });
    }

    @GameTest(maxTicks = 600)
    public void waterWithoutABucketIsLeftAndReported(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        h.setBlock(3, 2, 3, Blocks.WATER);
        Result result = new Result();
        TaskRunner runner = runner(companion, result);
        runner.start(clear(h, 2, 2, 2, false), "clear_area");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(result.status == TaskStatus.FAILED, "status " + result.status + ": " + result.summary);
            h.assertTrue(h.getBlockState(new BlockPos(3, 2, 3)).is(Blocks.WATER),
                    "the water must stay without a bucket");
            h.assertTrue(result.summary.contains("no empty bucket"), result.summary);
        });
    }

    @GameTest(maxTicks = 600)
    public void aBlockEntityIsNeverCleared(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        h.setBlock(3, 2, 2, Blocks.SPAWNER);
        Result result = new Result();
        TaskRunner runner = runner(companion, result);
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
        TaskRunner runner = runner(companion, result);
        runner.start(clear(h, 2, 2, 2, true), "clear_area");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + ": " + result.summary);
            h.assertTrue(h.getBlockState(new BlockPos(3, 2, 2)).isAir(),
                    "the stray block at 3,2,2: " + h.getBlockState(new BlockPos(3, 2, 2)));
            h.assertTrue(result.summary.contains("1 block"), result.summary);
        });
    }
}
