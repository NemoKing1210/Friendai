package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.server.execution.ExecutionCoordinator;
import dev.friendai.task.TaskListener;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.builtin.UseBlockTask;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

public class UseBlockTests {
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

    private static ExecutionCoordinator runner(CompanionEntity c, Result result) {
        ExecutionCoordinator r = new ExecutionCoordinator(c, () -> 300, () -> false);
        r.setListener(result);
        return r;
    }

    private static String coords(BlockPos p) {
        return p.getX() + " " + p.getY() + " " + p.getZ();
    }

    private static void doorAt(GameTestHelper h, Block door, int x, int y, int z, boolean open) {
        BlockState lower = door.defaultBlockState()
                .setValue(DoorBlock.FACING, Direction.NORTH)
                .setValue(DoorBlock.HINGE, DoorHingeSide.LEFT)
                .setValue(DoorBlock.OPEN, open)
                .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
        BlockState upper = lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER);
        h.setBlock(x, y, z, lower);
        h.setBlock(x, y + 1, z, upper);
    }

    @GameTest(maxTicks = 400)
    public void useOpensDoor(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        doorAt(h, Blocks.OAK_DOOR, 8, 2, 3, false);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        BlockPos target = h.absolutePos(new BlockPos(8, 2, 3));
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(new UseBlockTask(target, null), "use_block");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(
                    result.summary.equals("used minecraft:oak_door at " + coords(target) + ": now open"),
                    "summary was " + result.summary);
            h.assertTrue(h.getBlockState(new BlockPos(8, 2, 3)).getValue(DoorBlock.OPEN), "lower half must be open");
            h.assertTrue(h.getBlockState(new BlockPos(8, 3, 3)).getValue(DoorBlock.OPEN), "upper half must be open");
        });
    }

    @GameTest
    public void useWantAlreadyOpen(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        doorAt(h, Blocks.SPRUCE_DOOR, 8, 2, 3, true);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        BlockPos target = h.absolutePos(new BlockPos(8, 2, 3));
        var start = c.position();
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(new UseBlockTask(target, "open"), "use_block");
        runner.tick();
        h.assertTrue(result.status == TaskStatus.SUCCESS, "should succeed: " + result.status + " " + result.summary);
        h.assertTrue(
                result.summary.equals("minecraft:spruce_door at " + coords(target) + " is already open"),
                "summary was " + result.summary);
        h.assertTrue(c.position().equals(start), "the companion must not move");
        h.succeed();
    }

    @GameTest(maxTicks = 400)
    public void useLeverWantOn(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(8, 2, 3, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACING, Direction.NORTH));
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        BlockPos target = h.absolutePos(new BlockPos(8, 2, 3));
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(new UseBlockTask(target, "on"), "use_block");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(
                    result.summary.equals("used minecraft:lever at " + coords(target) + ": now on"),
                    "summary was " + result.summary);
            h.assertTrue(
                    h.getBlockState(new BlockPos(8, 2, 3)).getValue(BlockStateProperties.POWERED), "lever must be on");
        });
    }

    @GameTest(maxTicks = 400)
    public void useButtonPressed(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(8, 2, 3, Blocks.STONE_BUTTON);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        BlockPos target = h.absolutePos(new BlockPos(8, 2, 3));
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(new UseBlockTask(target, null), "use_block");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(
                    result.summary.equals("used minecraft:stone_button at " + coords(target) + ": pressed"),
                    "summary was " + result.summary);
        });
    }

    @GameTest(maxTicks = 400)
    public void useIronDoorFails(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        doorAt(h, Blocks.IRON_DOOR, 8, 2, 3, false);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        BlockPos target = h.absolutePos(new BlockPos(8, 2, 3));
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(new UseBlockTask(target, null), "use_block");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.FAILED, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(
                    result.summary.equals("minecraft:iron_door at " + coords(target) + " can't be used by hand"),
                    "summary was " + result.summary);
        });
    }

    @GameTest
    public void useNotWhitelisted(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(8, 2, 3, Blocks.ANVIL);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        BlockPos target = h.absolutePos(new BlockPos(8, 2, 3));
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(new UseBlockTask(target, null), "use_block");
        runner.tick();
        h.assertTrue(result.status == TaskStatus.FAILED, "should fail: " + result.status);
        h.assertTrue(
                result.summary.equals("I can only use doors, trapdoors, fence gates, levers and buttons; "
                        + coords(target) + " is minecraft:anvil"),
                "summary was " + result.summary);
        h.succeed();
    }

    @GameTest
    public void useWantNotApplicable(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        doorAt(h, Blocks.BIRCH_DOOR, 8, 2, 3, false);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        BlockPos target = h.absolutePos(new BlockPos(8, 2, 3));
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(new UseBlockTask(target, "on"), "use_block");
        runner.tick();
        h.assertTrue(result.status == TaskStatus.FAILED, "should fail: " + result.status);
        h.assertTrue(result.summary.equals("minecraft:birch_door can't be on"), "summary was " + result.summary);
        h.succeed();
    }
}
