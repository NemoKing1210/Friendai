package dev.friendai.test;

import dev.friendai.crafting.Station;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.features.fire.FireTarget;
import dev.friendai.features.navigation.pathing.Pos;
import dev.friendai.server.execution.ExecutionCoordinator;
import dev.friendai.task.TaskListener;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.builtin.ExtinguishFireTask;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.material.Fluids;

/** Fires: dousing a block, the bucket coming back, the portal frame, the companion's own fire and its campfire. */
public class FireTests {

    private static final class Result implements TaskListener {
        TaskStatus status;
        String summary;

        @Override
        public void onFinished(int id, String toolName, TaskStatus status, String summary) {
            this.status = status;
            this.summary = summary;
        }

        @Override
        public void onCancelled(int id, String toolName, String reason) {}
    }

    private static ExecutionCoordinator run(CompanionEntity c, Result result, ExtinguishFireTask task) {
        ExecutionCoordinator runner = new ExecutionCoordinator(c, () -> ExtinguishFireTask.TIMEOUT_TICKS, () -> false);
        runner.setListener(result);
        runner.start(task, "extinguish_fire");
        return runner;
    }

    private static Pos absolute(GameTestHelper h, int x, int y, int z) {
        BlockPos pos = h.absolutePos(new BlockPos(x, y, z));
        return Pos.of(pos.getX(), pos.getY(), pos.getZ());
    }

    @GameTest(maxTicks = 400)
    public void dousesABurningBlockAndRecoversTheBucket(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        h.setBlock(3, 1, 2, Blocks.NETHERRACK);
        h.setBlock(3, 2, 2, Blocks.FIRE);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.WATER_BUCKET));
        c.inventory().addItem(new ItemStack(Items.BUCKET));
        BlockPos fire = h.absolutePos(new BlockPos(3, 2, 2));
        Result result = new Result();
        ExecutionCoordinator runner =
                run(c, result, new ExtinguishFireTask(new FireTarget.Block(absolute(h, 3, 2, 2)), 8));

        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "the task did not finish");
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + ": " + result.summary);
            h.assertTrue(
                    h.getLevel().getBlockState(fire).isAir(),
                    "fire left behind: " + h.getLevel().getBlockState(fire));
            h.assertTrue(!h.getLevel().getFluidState(fire).getType().isSame(Fluids.WATER), "water left behind");
            h.assertTrue(
                    c.inventory().countItem(Items.WATER_BUCKET) == 1,
                    "water buckets back: " + c.inventory().countItem(Items.WATER_BUCKET));
            h.assertTrue(
                    c.inventory().countItem(Items.BUCKET) == 1,
                    "empty buckets left: " + c.inventory().countItem(Items.BUCKET));
        });
    }

    @GameTest(maxTicks = 400)
    public void doesNotTouchAFireInsideAPortalFrame(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        for (int y = 2; y <= 4; y++) {
            h.setBlock(2, y, 2, Blocks.OBSIDIAN);
            h.setBlock(4, y, 2, Blocks.OBSIDIAN);
        }
        h.setBlock(3, 2, 2, Blocks.OBSIDIAN);
        h.setBlock(3, 4, 2, Blocks.OBSIDIAN);
        h.setBlock(3, 3, 2, Blocks.FIRE);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 7, 2, 2);
        c.inventory().addItem(new ItemStack(Items.WATER_BUCKET));
        BlockPos fire = h.absolutePos(new BlockPos(3, 3, 2));
        BlockPos frame = h.absolutePos(new BlockPos(2, 3, 2));
        Result result = new Result();
        ExecutionCoordinator runner =
                run(c, result, new ExtinguishFireTask(new FireTarget.Block(absolute(h, 3, 3, 2)), 8));

        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "the task did not finish");
            h.assertTrue(result.status == TaskStatus.FAILED, "status " + result.status + ": " + result.summary);
            h.assertTrue(result.summary.contains("portal frame"), "reason: " + result.summary);
            h.assertTrue(h.getLevel().getBlockState(fire).is(Blocks.FIRE), "the fire was put out anyway");
            h.assertTrue(h.getLevel().getBlockState(frame).is(Blocks.OBSIDIAN), "the frame was damaged");
            h.assertTrue(
                    c.inventory().countItem(Items.WATER_BUCKET) == 1,
                    "the bucket was spent: " + c.inventory().countItem(Items.WATER_BUCKET));
        });
    }

    @GameTest(maxTicks = 400)
    public void dousesItsOwnFire(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.WATER_BUCKET));
        c.setRemainingFireTicks(200);
        // On its own the fire would burn for 200 ticks; the reflex has to douse it long before that.
        h.runAfterDelay(
                40, () -> h.assertTrue(!c.isOnFire(), "still burning after 40 ticks: the fire reflex did not run"));

        h.succeedWhen(() -> {
            h.assertTrue(!c.isOnFire(), "the companion is still on fire");
            h.assertTrue(
                    h.getLevel().getBlockState(c.blockPosition()).isAir(),
                    "water left at its feet: " + h.getLevel().getBlockState(c.blockPosition()));
            h.assertTrue(
                    c.inventory().countItem(Items.WATER_BUCKET) == 1,
                    "the bucket came back: " + c.inventory().countItem(Items.WATER_BUCKET));
        });
    }

    @GameTest(maxTicks = 600)
    public void nearDousesEveryFireItCanReachEvenPastARefusedOne(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        h.setBlock(3, 1, 2, Blocks.NETHERRACK);
        h.setBlock(3, 2, 2, Blocks.FIRE);
        h.setBlock(5, 1, 2, Blocks.NETHERRACK);
        h.setBlock(5, 2, 2, Blocks.FIRE);
        h.setBlock(4, 1, 2, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true));
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.WATER_BUCKET));
        BlockPos campfire = h.absolutePos(new BlockPos(4, 1, 2));
        c.stationLease().keep(Station.CAMPFIRE, campfire.asLong(), h.getLevel().getGameTime());
        BlockPos first = h.absolutePos(new BlockPos(3, 2, 2));
        BlockPos second = h.absolutePos(new BlockPos(5, 2, 2));
        Result result = new Result();
        ExecutionCoordinator runner = run(c, result, ExtinguishFireTask.near(8));

        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "the task did not finish");
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + ": " + result.summary);
            h.assertTrue(
                    h.getLevel().getBlockState(first).isAir(),
                    "the first fire is still there: " + h.getLevel().getBlockState(first));
            h.assertTrue(
                    h.getLevel().getBlockState(second).isAir(),
                    "the second fire is still there: " + h.getLevel().getBlockState(second));
            h.assertTrue(
                    h.getLevel().getBlockState(campfire).getValue(CampfireBlock.LIT),
                    "the cooking campfire was put out");
        });
    }

    @GameTest(maxTicks = 600)
    public void nearReachesTheCornerOfTheScanCube(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        h.setBlock(7, 1, 7, Blocks.NETHERRACK);
        h.setBlock(7, 2, 7, Blocks.FIRE);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 1, 2, 1);
        BlockPos fire = h.absolutePos(new BlockPos(7, 2, 7));
        Result result = new Result();
        ExecutionCoordinator runner = run(c, result, ExtinguishFireTask.near(8));

        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "the task did not finish");
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + ": " + result.summary);
            h.assertTrue(
                    h.getLevel().getBlockState(fire).isAir(),
                    "the corner fire is still there: " + h.getLevel().getBlockState(fire));
        });
    }

    @GameTest(maxTicks = 400)
    public void takesTheWaterBackWhenStoppedMidDouse(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        h.setBlock(3, 1, 2, Blocks.NETHERRACK);
        h.setBlock(3, 2, 2, Blocks.FIRE);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.WATER_BUCKET));
        c.inventory().addItem(new ItemStack(Items.BUCKET));
        BlockPos fire = h.absolutePos(new BlockPos(3, 2, 2));
        Result result = new Result();
        ExecutionCoordinator runner =
                run(c, result, new ExtinguishFireTask(new FireTarget.Block(absolute(h, 3, 2, 2)), 8));
        runner.tick();
        runner.stop("test stop");

        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(
                    h.getLevel().getFluidState(fire).isEmpty()
                            || !h.getLevel().getFluidState(fire).isSource(),
                    "a source was left behind: " + h.getLevel().getBlockState(fire));
            h.assertTrue(
                    c.inventory().countItem(Items.WATER_BUCKET) == 1,
                    "the bucket did not come back: " + c.inventory().countItem(Items.WATER_BUCKET));
        });
    }

    @GameTest(maxTicks = 400)
    public void leavesTheCookingCampfireLit(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        h.setBlock(3, 1, 2, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true));
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.WATER_BUCKET));
        BlockPos campfire = h.absolutePos(new BlockPos(3, 1, 2));
        c.stationLease().keep(Station.CAMPFIRE, campfire.asLong(), h.getLevel().getGameTime());
        Result result = new Result();
        ExecutionCoordinator runner =
                run(c, result, new ExtinguishFireTask(new FireTarget.Block(absolute(h, 3, 1, 2)), 8));

        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "the task did not finish");
            h.assertTrue(result.status == TaskStatus.FAILED, "status " + result.status + ": " + result.summary);
            h.assertTrue(result.summary.contains("cooking station"), "reason: " + result.summary);
            h.assertTrue(
                    h.getLevel().getBlockState(campfire).getValue(CampfireBlock.LIT),
                    "the cooking campfire was put out");
            h.assertTrue(
                    c.inventory().countItem(Items.WATER_BUCKET) == 1,
                    "the bucket was spent: " + c.inventory().countItem(Items.WATER_BUCKET));
        });
    }
}
