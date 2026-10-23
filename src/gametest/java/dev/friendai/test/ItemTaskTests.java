package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.task.TaskListener;
import dev.friendai.task.TaskRunner;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.builtin.DropItemsTask;
import dev.friendai.task.builtin.GiveItemsTask;
import dev.friendai.task.builtin.PickupItemsTask;
import dev.friendai.task.inventory.InventoryOps;
import dev.friendai.task.inventory.ItemMatcher;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class ItemTaskTests {
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

    private static TaskRunner runner(CompanionEntity c, Result result) {
        TaskRunner r = new TaskRunner(c, () -> 300, () -> false);
        r.setListener(result);
        return r;
    }

    private static ItemEntity dropItem(GameTestHelper h, double x, double y, double z, ItemStack stack) {
        Vec3 p = h.absoluteVec(new Vec3(x, y, z));
        ItemEntity e = new ItemEntity(h.getLevel(), p.x, p.y, p.z, stack);
        e.setDeltaMovement(Vec3.ZERO);
        h.getLevel().addFreshEntity(e);
        return e;
    }

    @GameTest(maxTicks = 600)
    public void pickupCollectsAllInRadius(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        dropItem(h, 5.5, 2, 2.5, new ItemStack(Items.COBBLESTONE, 3));
        dropItem(h, 2.5, 2, 6.5, new ItemStack(Items.OAK_LOG, 2));
        dropItem(h, 4.5, 2, 4.5, new ItemStack(Items.DIRT, 1));
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        runner.start(new PickupItemsTask(6), "pickup_items");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished yet");
            h.assertTrue(c.inventory().countItem(Items.COBBLESTONE) == 3, "cobblestone missing");
            h.assertTrue(c.inventory().countItem(Items.OAK_LOG) == 2, "logs missing");
            h.assertTrue(c.inventory().countItem(Items.DIRT) == 1, "dirt missing");
            h.assertTrue(result.summary.equals("picked up 6 items"), "summary was " + result.summary);
        });
    }

    @GameTest(maxTicks = 400)
    public void pickupFilterTakesOnlyMatching(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        dropItem(h, 4.5, 2, 2.5, new ItemStack(Items.EGG, 3));
        ItemEntity cobblestone = dropItem(h, 2.5, 2, 5.5, new ItemStack(Items.COBBLESTONE, 2));
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        runner.start(new PickupItemsTask(6, ItemMatcher.parse("minecraft:egg").orElseThrow()), "pickup_items");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished yet");
            h.assertValueEqual(c.inventory().countItem(Items.EGG), 3, "eggs");
            h.assertTrue(!cobblestone.isRemoved(), "cobblestone should stay on the ground");
            h.assertTrue(result.summary.equals("picked up 3 items"), "summary was " + result.summary);
        });
    }

    @GameTest(maxTicks = 100)
    public void pickupIgnoresOutsideRadius(GameTestHelper h) {
        GameTestSupport.platform(h, 32);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ItemEntity far = dropItem(h, 25.5, 2, 2.5, new ItemStack(Items.COBBLESTONE, 3));
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        runner.start(new PickupItemsTask(6), "pickup_items");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished yet");
            h.assertTrue(result.summary.equals("picked up 0 items"), "summary was " + result.summary);
            h.assertTrue(!far.isRemoved(), "far item should be untouched");
        });
    }

    @GameTest(maxTicks = 400)
    public void giveDropsTowardOwner(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 8.5, 2, 2.5);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.setOwner(owner.getUUID());
        c.inventory().addItem(new ItemStack(Items.OAK_LOG, 10));
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        runner.start(new GiveItemsTask(ItemMatcher.parse("minecraft:oak_log").orElseThrow(), 4), "give_items");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished yet");
            int dropped = h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(owner.blockPosition()).inflate(8)).stream()
                    .filter(e -> e.getItem().is(Items.OAK_LOG))
                    .mapToInt(e -> e.getItem().getCount()).sum();
            h.assertTrue(dropped == 4, "dropped " + dropped);
            h.assertTrue(c.inventory().countItem(Items.OAK_LOG) == 6, "should have 6 left");
            h.assertTrue(result.summary.equals("gave 4 minecraft:oak_log"), "summary was " + result.summary);
        });
    }

    @GameTest
    public void giveWithoutItemFails(GameTestHelper h) {
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 3, 2, 2);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.setOwner(owner.getUUID());
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        runner.start(new GiveItemsTask(ItemMatcher.parse("minecraft:diamond").orElseThrow(), 1), "give_items");
        runner.tick();
        h.assertTrue(result.status == TaskStatus.FAILED, "should fail");
        h.assertTrue(result.summary.equals("I don't have minecraft:diamond"), "summary was " + result.summary);
        h.succeed();
    }

    @GameTest(maxTicks = 400)
    public void giveAcceptsTag(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 5.5, 2, 2.5);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.setOwner(owner.getUUID());
        c.inventory().addItem(new ItemStack(Items.BIRCH_PLANKS, 3));
        c.inventory().addItem(new ItemStack(Items.SPRUCE_PLANKS, 2));
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        runner.start(new GiveItemsTask(ItemMatcher.parse("#minecraft:planks").orElseThrow(), 4), "give_items");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished yet");
            h.assertTrue(result.summary.equals("gave 4 #minecraft:planks"), "summary was " + result.summary);
            int left = c.inventory().countItem(Items.BIRCH_PLANKS) + c.inventory().countItem(Items.SPRUCE_PLANKS);
            h.assertTrue(left == 1, "should have 1 plank left, had " + left);
        });
    }

    @GameTest
    public void itemMatcherParsing(GameTestHelper h) {
        h.assertTrue(ItemMatcher.parse("nonsense:item").isEmpty(), "unknown id must not parse");
        h.assertTrue(ItemMatcher.parse(" oak_log ").orElseThrow().label().equals("minecraft:oak_log"), "label normalized");
        h.assertTrue(ItemMatcher.parse("#minecraft:wool").orElseThrow().test(new ItemStack(Items.WOOL.red())), "tag matches");
        h.assertTrue(!ItemMatcher.parse("minecraft:oak_log").orElseThrow().test(ItemStack.EMPTY), "empty never matches");
        h.succeed();
    }

    @GameTest
    public void inventoryOpsRemoveAcrossSlots(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ItemMatcher matcher = ItemMatcher.parse("minecraft:cobbled_deepslate").orElseThrow();
        c.inventory().setItem(0, new ItemStack(Items.COBBLED_DEEPSLATE, 40));
        c.inventory().setItem(5, new ItemStack(Items.COBBLED_DEEPSLATE, 30));
        List<ItemStack> removed = InventoryOps.remove(c.inventory(), matcher, 50);
        int total = removed.stream().mapToInt(ItemStack::getCount).sum();
        h.assertTrue(total == 50, "removed " + total);
        h.assertTrue(removed.stream().allMatch(s -> s.getCount() <= s.getMaxStackSize()), "a stack went over its limit");
        h.assertTrue(InventoryOps.count(c.inventory(), matcher) == 20, "should have 20 left");
        h.succeed();
    }

    @GameTest
    public void insertOrDropSpillsLeftover(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        for (int i = 0; i < c.inventory().getContainerSize(); i++) {
            c.inventory().setItem(i, new ItemStack(Items.STONE, 64));
        }
        InventoryOps.insertOrDrop(c, new ItemStack(Items.PRISMARINE, 5));
        h.assertTrue(c.inventory().countItem(Items.PRISMARINE) == 0, "a full inventory must take nothing");
        int dropped = h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(c.blockPosition()).inflate(2)).stream()
                .filter(e -> e.getItem().is(Items.PRISMARINE))
                .mapToInt(e -> e.getItem().getCount()).sum();
        h.assertTrue(dropped == 5, "dropped " + dropped);
        h.succeed();
    }

    @GameTest
    public void dropById(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.GRANITE, 10));
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        runner.start(new DropItemsTask(ItemMatcher.parse("minecraft:granite").orElseThrow(), 7), "drop_items");
        runner.tick();
        h.assertTrue(result.status == TaskStatus.SUCCESS, "should succeed");
        h.assertTrue(result.summary.equals("dropped 7 minecraft:granite"), "summary was " + result.summary);
        h.assertTrue(c.inventory().countItem(Items.GRANITE) == 3, "should have 3 left");
        int dropped = h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(c.blockPosition()).inflate(2)).stream()
                .filter(e -> e.getItem().is(Items.GRANITE))
                .mapToInt(e -> e.getItem().getCount()).sum();
        h.assertTrue(dropped == 7, "dropped " + dropped);
        h.succeed();
    }

    @GameTest
    public void dropByTag(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.WOOL.white(), 2));
        c.inventory().addItem(new ItemStack(Items.WOOL.lime(), 3));
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        runner.start(new DropItemsTask(ItemMatcher.parse("#minecraft:wool").orElseThrow(), 4), "drop_items");
        runner.tick();
        h.assertTrue(result.status == TaskStatus.SUCCESS, "should succeed");
        h.assertTrue(result.summary.equals("dropped 4 #minecraft:wool"), "summary was " + result.summary);
        int left = c.inventory().countItem(Items.WOOL.white()) + c.inventory().countItem(Items.WOOL.lime());
        h.assertTrue(left == 1, "should have 1 wool left, had " + left);
        h.succeed();
    }

    @GameTest
    public void dropNothingFails(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        runner.start(new DropItemsTask(ItemMatcher.parse("minecraft:andesite").orElseThrow(), 1), "drop_items");
        runner.tick();
        h.assertTrue(result.status == TaskStatus.FAILED, "should fail");
        h.assertTrue(result.summary.equals("I don't have minecraft:andesite"), "summary was " + result.summary);
        h.succeed();
    }

    @GameTest(maxTicks = 300)
    public void ownDropsAreNotCollectedAfterTheDelay(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.TERRACOTTA, 5));
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        runner.start(new DropItemsTask(ItemMatcher.parse("minecraft:terracotta").orElseThrow(), 5), "drop_items");
        int[] ticks = {0};
        h.succeedWhen(() -> {
            ticks[0]++;
            if (ticks[0] == 120) {
                runner.start(new PickupItemsTask(6), "pickup_items");
            }
            runner.tick();
            h.assertTrue(ticks[0] >= 121, "waiting for the pickup delay to expire");
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(result.summary.equals("picked up 0 items"), "summary was " + result.summary);
            h.assertTrue(c.inventory().countItem(Items.TERRACOTTA) == 0, "picked its own drop back up after the pickup delay expired");
            int onGround = h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(c.blockPosition()).inflate(6)).stream()
                    .filter(e -> e.getItem().is(Items.TERRACOTTA))
                    .mapToInt(e -> e.getItem().getCount()).sum();
            h.assertTrue(onGround == 5, "on the ground " + onGround);
        });
    }

    @GameTest(maxTicks = 200)
    public void droppedItemsAreNotPickedUpBack(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.POLISHED_DIORITE, 5));
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        runner.start(new DropItemsTask(ItemMatcher.parse("minecraft:polished_diorite").orElseThrow(), 5), "drop_items");
        runner.tick();
        h.assertTrue(result.status == TaskStatus.SUCCESS, "drop should succeed");
        runner.start(new PickupItemsTask(6), "pickup_items");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished yet");
            h.assertTrue(result.summary.equals("picked up 0 items"), "summary was " + result.summary);
            h.assertTrue(c.inventory().countItem(Items.POLISHED_DIORITE) == 0, "must not pick its own drop back up");
            int onGround = h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(c.blockPosition()).inflate(4)).stream()
                    .filter(e -> e.getItem().is(Items.POLISHED_DIORITE))
                    .mapToInt(e -> e.getItem().getCount()).sum();
            h.assertTrue(onGround == 5, "on the ground " + onGround);
        });
    }
}
