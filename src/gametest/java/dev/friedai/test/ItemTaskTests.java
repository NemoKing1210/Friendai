package dev.friedai.test;

import dev.friedai.entity.CompanionEntity;
import dev.friedai.entity.FriedaiEntities;
import dev.friedai.task.TaskListener;
import dev.friedai.task.TaskRunner;
import dev.friedai.task.TaskStatus;
import dev.friedai.task.builtin.GiveItemsTask;
import dev.friedai.task.builtin.PickupItemsTask;
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
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
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

    @GameTest(maxTicks = 100)
    public void pickupIgnoresOutsideRadius(GameTestHelper h) {
        GameTestSupport.platform(h, 32);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
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
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        c.setOwner(owner.getUUID());
        c.inventory().addItem(new ItemStack(Items.OAK_LOG, 10));
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        runner.start(new GiveItemsTask(Items.OAK_LOG, 4), "give_items");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished yet");
            int dropped = h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(owner.blockPosition()).inflate(8)).stream()
                    .mapToInt(e -> e.getItem().getCount()).sum();
            h.assertTrue(dropped == 4, "dropped " + dropped);
            h.assertTrue(c.inventory().countItem(Items.OAK_LOG) == 6, "should have 6 left");
            h.assertTrue(result.summary.equals("gave 4 minecraft:oak_log"), "summary was " + result.summary);
        });
    }

    @GameTest
    public void giveWithoutItemFails(GameTestHelper h) {
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 3, 2, 2);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        c.setOwner(owner.getUUID());
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        runner.start(new GiveItemsTask(Items.DIAMOND, 1), "give_items");
        runner.tick();
        h.assertTrue(result.status == TaskStatus.FAILED, "should fail");
        h.assertTrue(result.summary.equals("I don't have minecraft:diamond"), "summary was " + result.summary);
        h.succeed();
    }
}
