package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.features.interaction.minecraft.task.UseBlockTask;
import dev.friendai.server.execution.ExecutionCoordinator;
import dev.friendai.server.execution.TaskEventSink;
import dev.friendai.task.TaskStatus;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

public class UseBlockTests {
    private static final class Result implements TaskEventSink {
        TaskStatus status;
        String summary;
        boolean cancelled;

        @Override
        public void onFinished(int id, String toolName, TaskStatus status, String summary) {
            this.status = status;
            this.summary = summary;
        }

        @Override
        public void onCancelled(int id, String toolName, String reason) {
            cancelled = true;
        }
    }

    private static ExecutionCoordinator runner(CompanionEntity c, Result result) {
        ExecutionCoordinator r = new ExecutionCoordinator(c, () -> 300, () -> false);
        r.setEventSink(result);
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

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 400)
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

    @GameTest(structure = GameTestArenas.ARENA_16)
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

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 400)
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

    @GameTest(structure = GameTestArenas.ARENA_16)
    public void useLeverWantOff(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        BlockPos relative = new BlockPos(8, 2, 3);
        h.setBlock(
                relative,
                Blocks.LEVER
                        .defaultBlockState()
                        .setValue(LeverBlock.FACE, AttachFace.FLOOR)
                        .setValue(LeverBlock.POWERED, true));
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 6, 2, 3);
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(new UseBlockTask(h.absolutePos(relative), "off"), "use_block");
        TaskTrace.of(h, runner, c).succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "lever did not turn off: " + result.summary);
            h.assertFalse(powered(h, relative), "lever must be off");
        });
    }

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 400)
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

    private static boolean powered(GameTestHelper h, BlockPos relative) {
        return h.getBlockState(relative).getValue(BlockStateProperties.POWERED);
    }

    private static BlockState stoneButton() {
        return Blocks.STONE_BUTTON.defaultBlockState().setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR);
    }

    private static BlockPos pressButton(GameTestHelper h, CompanionEntity c, BlockPos relative) {
        h.setBlock(relative, stoneButton());
        BlockPos target = h.absolutePos(relative);
        c.hands().use(target);
        h.assertTrue(powered(h, relative), "fixture button must be powered");
        h.assertTrue(
                h.getLevel().getBlockTicks().hasScheduledTick(target, Blocks.STONE_BUTTON),
                "fixture must schedule a vanilla release");
        return target;
    }

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 60)
    public void usePoweredButtonWaitsForRelease(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        BlockPos relative = new BlockPos(8, 2, 3);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 6, 2, 3);
        BlockPos target = pressButton(h, c, relative);
        var start = c.position();
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(new UseBlockTask(target, "off"), "use_block");
        runner.tick();
        h.assertTrue(
                runner.current().isPresent() && result.status == null && powered(h, relative),
                "job must wait for vanilla release: " + result.status + " " + result.summary);
        TaskTrace.of(h, runner, c).succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "still waiting for release: " + result.summary);
            h.assertFalse(powered(h, relative), "button must be off at completion");
            h.assertFalse(
                    h.getLevel().getBlockTicks().hasScheduledTick(target, Blocks.STONE_BUTTON),
                    "the release must not schedule a second press");
            h.assertTrue(c.position().equals(start), "waiting must not move the companion");
        });
    }

    @GameTest(structure = GameTestArenas.ARENA_16)
    public void useButtonWantOnIsIdempotent(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        BlockPos relative = new BlockPos(8, 2, 3);
        h.setBlock(relative, stoneButton());
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 6, 2, 3);
        BlockPos target = h.absolutePos(relative);
        Result pressed = new Result();
        ExecutionCoordinator runner = runner(c, pressed);
        runner.start(new UseBlockTask(target, "on"), "use_block");
        runner.tick();
        h.assertTrue(
                pressed.status == TaskStatus.SUCCESS && powered(h, relative),
                "want=on must press the unpowered button: " + pressed.summary);

        Result alreadyOn = new Result();
        runner.setEventSink(alreadyOn);
        runner.start(new UseBlockTask(target, "on"), "use_block");
        runner.tick();
        h.assertTrue(
                alreadyOn.status == TaskStatus.SUCCESS && powered(h, relative),
                "already-on must succeed without changing the button: " + alreadyOn.summary);
        h.assertTrue(alreadyOn.summary.endsWith("already on"), "already-on response must not claim a fresh press");
        h.succeed();
    }

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 60)
    public void useButtonReportsWhenItStaysPowered(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        BlockPos relative = new BlockPos(8, 2, 3);
        h.setBlock(relative, stoneButton().setValue(BlockStateProperties.POWERED, true));
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 6, 2, 3);
        BlockPos target = h.absolutePos(relative);
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(new UseBlockTask(target, "off"), "use_block");
        runner.tick();
        h.assertTrue(runner.current().isPresent(), "job must initially wait for release");
        TaskTrace.of(h, runner, c).succeedWhen(() -> {
            runner.tick();
            h.assertTrue(
                    result.status == TaskStatus.FAILED && result.summary.contains("stayed on"),
                    "held button needs a bounded release failure: " + result.summary);
            h.assertTrue(h.getTick() <= 45 && powered(h, relative), "task must time out without changing the button");
            h.assertFalse(
                    h.getLevel().getBlockTicks().hasScheduledTick(target, Blocks.STONE_BUTTON),
                    "waiting must not schedule a release");
        });
    }

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 60)
    public void cancelButtonWaitLeavesVanillaReleaseAlone(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        BlockPos relative = new BlockPos(8, 2, 3);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 6, 2, 3);
        BlockPos target = pressButton(h, c, relative);
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(new UseBlockTask(target, "off"), "use_block");
        runner.tick();
        h.assertTrue(runner.current().isPresent(), "task must be waiting before cancellation");
        runner.stop("cancelled during button wait");
        h.assertTrue(result.cancelled && powered(h, relative), "cancellation must leave the powered button alone");
        TaskTrace.of(h, runner, c).succeedWhen(() -> {
            runner.tick();
            h.assertFalse(powered(h, relative), "vanilla release must still happen");
            h.assertTrue(result.status == null, "the cancelled task reported completion");
        });
    }

    @GameTest(structure = GameTestArenas.ARENA_16)
    public void useButtonWaitDoesNotUseReplacement(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        BlockPos relative = new BlockPos(8, 2, 3);
        h.setBlock(relative, stoneButton().setValue(BlockStateProperties.POWERED, true));
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 6, 2, 3);
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(new UseBlockTask(h.absolutePos(relative), "off"), "use_block");
        runner.tick();
        h.assertTrue(runner.current().isPresent(), "job must initially wait for release");

        h.setBlock(
                relative,
                Blocks.LEVER
                        .defaultBlockState()
                        .setValue(LeverBlock.FACE, AttachFace.FLOOR)
                        .setValue(LeverBlock.POWERED, true));
        runner.tick();
        h.assertTrue(
                result.status == TaskStatus.FAILED && result.summary.contains("replaced"),
                "replaced button must not complete: " + result.summary);
        h.assertTrue(powered(h, relative), "replacement lever must stay powered");
        h.succeed();
    }

    @GameTest(structure = GameTestArenas.ARENA_16)
    public void useProtectedBlocksRefuses(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        BlockPos lever = new BlockPos(8, 2, 3);
        BlockPos button = new BlockPos(8, 2, 4);
        h.setBlock(lever, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR));
        h.setBlock(button, stoneButton().setValue(BlockStateProperties.POWERED, true));
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 3);
        BlockPos leverTarget = h.absolutePos(lever);
        BlockPos buttonTarget = h.absolutePos(button);
        c.hands().overrideProtection(pos -> pos.equals(leverTarget) || pos.equals(buttonTarget));
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(new UseBlockTask(leverTarget, "on"), "use_block");
        runner.tick();
        h.assertTrue(
                result.status == TaskStatus.FAILED && result.summary.contains("protected"),
                "protected lever must fail: " + result.summary);
        h.assertFalse(powered(h, lever), "protected lever must stay off");

        Result protectedButton = new Result();
        runner.setEventSink(protectedButton);
        runner.start(new UseBlockTask(buttonTarget, "off"), "use_block");
        runner.tick();
        h.assertTrue(
                protectedButton.status == TaskStatus.FAILED && protectedButton.summary.contains("protected"),
                "protected button must fail: " + protectedButton.summary);
        h.assertTrue(powered(h, button), "protected button must stay on");
        h.succeed();
    }

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 400)
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

    @GameTest(structure = GameTestArenas.ARENA_16)
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

    @GameTest(structure = GameTestArenas.ARENA_16)
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
