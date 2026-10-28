package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.server.execution.ExecutionCoordinator;
import dev.friendai.task.TaskListener;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.builtin.PlaceBlockTask;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;

public class PlaceBlockTests {
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

    private static PlaceBlockTask place(Item item, BlockPos target) {
        return new PlaceBlockTask((BlockItem) item, target);
    }

    private static String coords(BlockPos p) {
        return p.getX() + " " + p.getY() + " " + p.getZ();
    }

    @GameTest(maxTicks = 400)
    public void placeAtCoordinates(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.SMOOTH_STONE));
        BlockPos target = h.absolutePos(new BlockPos(8, 2, 3));
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        PlaceBlockTask task = place(Items.SMOOTH_STONE, target);
        runner.start(task, "place_block");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(
                    result.summary.equals("placed minecraft:smooth_stone at " + coords(target)),
                    "summary was " + result.summary);
            h.assertTrue(h.getBlockState(new BlockPos(8, 2, 3)).is(Blocks.SMOOTH_STONE), "block must be placed");
            h.assertTrue(c.inventory().countItem(Items.SMOOTH_STONE) == 0, "the item must be used");
            h.assertTrue(task.placedAt().equals(target), "placedAt was " + task.placedAt());
        });
    }

    @GameTest(maxTicks = 400)
    public void placeFurnaceFacesCompanion(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.FURNACE));
        BlockPos target = h.absolutePos(new BlockPos(8, 2, 3));
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(place(Items.FURNACE, target), "place_block");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(
                    h.getBlockState(new BlockPos(8, 2, 3)).getValue(AbstractFurnaceBlock.FACING) == Direction.WEST,
                    "facing was " + h.getBlockState(new BlockPos(8, 2, 3)).getValue(AbstractFurnaceBlock.FACING));
        });
    }

    @GameTest(maxTicks = 400)
    public void placeNearbyAvoidsOwnerAndSelf(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        var owner = GameTestSupport.mockPlayerAt(h, 4, 2, 4);
        c.setOwner(owner.getUUID());
        c.inventory().addItem(new ItemStack(Items.CRAFTING_TABLE));
        BlockPos start = c.blockPosition();
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        PlaceBlockTask task = place(Items.CRAFTING_TABLE, null);
        runner.start(task, "place_block");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            BlockPos placed = task.placedAt();
            h.assertTrue(
                    h.getLevel().getBlockState(placed).is(Blocks.CRAFTING_TABLE), "table must be placed at " + placed);
            h.assertTrue(Math.sqrt(start.distSqr(placed)) <= 4.0, "placed too far away: " + placed);
            h.assertTrue(!placed.equals(c.blockPosition()), "must not place inside itself");
            h.assertTrue(
                    !placed.equals(owner.blockPosition())
                            && !placed.equals(owner.blockPosition().above()),
                    "must not place in the owner's cell");
            h.assertTrue(!h.getLevel().getBlockState(placed.below()).isAir(), "needs a floor below");
        });
    }

    @GameTest
    public void placeOccupiedFails(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(8, 2, 3, Blocks.BRICKS);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.MUD_BRICKS));
        BlockPos target = h.absolutePos(new BlockPos(8, 2, 3));
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(place(Items.MUD_BRICKS, target), "place_block");
        runner.tick();
        h.assertTrue(result.status == TaskStatus.FAILED, "should fail: " + result.status);
        h.assertTrue(
                result.summary.equals(coords(target) + " is occupied by minecraft:bricks"),
                "summary was " + result.summary);
        h.succeed();
    }

    @GameTest
    public void placeNothingToPlaceAgainst(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.END_STONE));
        BlockPos target = h.absolutePos(new BlockPos(8, 5, 3));
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(place(Items.END_STONE, target), "place_block");
        runner.tick();
        h.assertTrue(result.status == TaskStatus.FAILED, "should fail: " + result.status);
        h.assertTrue(
                result.summary.equals("nothing to place against at " + coords(target)),
                "summary was " + result.summary);
        h.succeed();
    }

    @GameTest
    public void placeWithoutItemFails(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        BlockPos target = h.absolutePos(new BlockPos(8, 2, 3));
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(place(Items.END_STONE, target), "place_block");
        runner.tick();
        h.assertTrue(result.status == TaskStatus.FAILED, "should fail: " + result.status);
        h.assertTrue(result.summary.equals("I don't have minecraft:end_stone"), "summary was " + result.summary);
        h.succeed();
    }

    @GameTest(maxTicks = 400)
    public void placeUsesMainHand(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.PURPUR_BLOCK));
        BlockPos target = h.absolutePos(new BlockPos(8, 2, 3));
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(place(Items.PURPUR_BLOCK, target), "place_block");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(h.getBlockState(new BlockPos(8, 2, 3)).is(Blocks.PURPUR_BLOCK), "block must be placed");
            h.assertTrue(c.getMainHandItem().isEmpty(), "the main hand must be empty");
        });
    }

    @GameTest(maxTicks = 400)
    public void placeRefusedReturnsItem(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.SUGAR_CANE));
        BlockPos target = h.absolutePos(new BlockPos(8, 2, 3));
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(place(Items.SUGAR_CANE, target), "place_block");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.FAILED, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(
                    result.summary.equals("couldn't place minecraft:sugar_cane at " + coords(target)),
                    "summary was " + result.summary);
            h.assertTrue(
                    c.inventory().countItem(Items.SUGAR_CANE) == 1,
                    "item must come back, have " + c.inventory().countItem(Items.SUGAR_CANE));
            h.assertTrue(c.hands().player().getInventory().isEmpty(), "the fake player inventory must be empty");
        });
    }

    @GameTest(maxTicks = 400)
    public void placeSpotFilledWhileWalking(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.RED_NETHER_BRICKS));
        BlockPos target = h.absolutePos(new BlockPos(12, 2, 3));
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(place(Items.RED_NETHER_BRICKS, target), "place_block");
        int[] ticks = {0};
        h.succeedWhen(() -> {
            runner.tick();
            if (++ticks[0] == 3) {
                h.setBlock(12, 2, 3, Blocks.NETHER_BRICKS);
            }
            h.assertTrue(ticks[0] >= 4, "waiting for the block change");
            h.assertTrue(result.status == TaskStatus.FAILED, "not failed: " + result.status + " " + result.summary);
            h.assertTrue(
                    result.summary.equals(coords(target) + " is occupied by minecraft:nether_bricks"),
                    "summary was " + result.summary);
        });
    }

    @GameTest(maxTicks = 400)
    public void placeWallTorchUsesWallVariant(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(8, 3, 3, Blocks.STONE);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.TORCH));
        BlockPos target = h.absolutePos(new BlockPos(8, 3, 4));
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(place(Items.TORCH, target), "place_block");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(
                    result.summary.equals("placed minecraft:torch at " + coords(target)),
                    "summary was " + result.summary);
            h.assertTrue(
                    h.getBlockState(new BlockPos(8, 3, 4)).is(Blocks.WALL_TORCH),
                    "wall torch expected, got " + h.getBlockState(new BlockPos(8, 3, 4)));
            h.assertTrue(c.inventory().countItem(Items.TORCH) == 0, "the torch must be consumed");
        });
    }

    @GameTest(maxTicks = 400)
    public void placeProtectedFails(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.QUARTZ_BLOCK));
        BlockPos target = h.absolutePos(new BlockPos(8, 2, 3));
        c.hands().overrideProtection(p -> p.equals(target));
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(place(Items.QUARTZ_BLOCK, target), "place_block");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.FAILED, "not failed: " + result.status + " " + result.summary);
            h.assertTrue(
                    result.summary.contains("is protected by the server"),
                    "summary must name the server: " + result.summary);
            h.assertTrue(c.inventory().countItem(Items.QUARTZ_BLOCK) == 1, "item must come back");
        });
    }
}
