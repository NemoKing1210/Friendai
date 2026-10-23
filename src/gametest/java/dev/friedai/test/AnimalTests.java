package dev.friedai.test;

import dev.friedai.entity.CompanionEntity;
import dev.friedai.entity.FriedaiEntities;
import dev.friedai.task.TaskListener;
import dev.friedai.task.TaskRunner;
import dev.friedai.task.TaskStatus;
import dev.friedai.task.builtin.BreedTask;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.cow.MushroomCow;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.animal.rabbit.Rabbit;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class AnimalTests {
    private static final class Result implements TaskListener {
        TaskStatus status;
        String summary;
        int cancelled;

        @Override
        public void onFinished(int id, String toolName, TaskStatus status, String summary) {
            this.status = status;
            this.summary = summary;
        }

        @Override
        public void onCancelled(int id, String toolName, String reason) {
            cancelled++;
        }
    }

    private static TaskRunner runner(CompanionEntity c, Result result) {
        TaskRunner runner = new TaskRunner(c, () -> 600, () -> false);
        runner.setListener(result);
        return runner;
    }

    @GameTest(maxTicks = 100)
    public void interactReturnsFilledBucket(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        Cow cow = h.spawn(EntityTypes.COW, 4, 2, 2);
        InteractionResult result = c.hands().interact(cow, new ItemStack(Items.BUCKET));
        h.assertTrue(result.consumesAction(), "interaction failed: " + result);
        h.assertValueEqual(c.inventory().countItem(Items.MILK_BUCKET), 1, "milk buckets");
        h.assertValueEqual(c.inventory().countItem(Items.BUCKET), 0, "empty buckets");
        Inventory fake = c.hands().player().getInventory();
        int left = 0;
        for (int i = 0; i < fake.getContainerSize(); i++) {
            left += fake.getItem(i).getCount();
        }
        h.assertValueEqual(left, 0, "items left in the fake player's inventory");
        h.succeed();
    }

    @GameTest(maxTicks = 600)
    public void breedsCows(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        h.spawn(EntityTypes.COW, 5, 2, 4);
        h.spawn(EntityTypes.COW, 7, 2, 4);
        c.inventory().addItem(new ItemStack(Items.WHEAT, 2));
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        runner.start(new BreedTask(EntityTypes.COW, 1), "breed_animals");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(result.summary.startsWith("fed 1 pairs of minecraft:cow"), "summary was " + result.summary);
            Vec3 at = h.absoluteVec(new Vec3(5, 2, 4));
            boolean baby = h.getLevel()
                    .getEntitiesOfClass(Cow.class, new AABB(at, at).inflate(16))
                    .stream().anyMatch(Cow::isBaby);
            h.assertTrue(baby, "no baby cow");
        });
    }

    @GameTest
    public void breedLimit(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        for (int i = 0; i < 24; i++) {
            h.spawn(EntityTypes.PIG, 4 + (i % 6), 2, 4 + (i / 6));
        }
        c.inventory().addItem(new ItemStack(Items.CARROT, 2));
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        runner.start(new BreedTask(EntityTypes.PIG, 1), "breed_animals");
        runner.tick();
        h.assertValueEqual(result.status, TaskStatus.FAILED, "status");
        h.assertValueEqual(result.summary, "there are already 24 minecraft:pig here", "summary");
        h.succeed();
    }

    @GameTest
    public void breedNoFood(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        h.spawn(EntityTypes.SHEEP, 5, 2, 4);
        h.spawn(EntityTypes.SHEEP, 7, 2, 4);
        c.inventory().addItem(new ItemStack(Items.DIRT, 2));
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        runner.start(new BreedTask(EntityTypes.SHEEP, 1), "breed_animals");
        runner.tick();
        h.assertValueEqual(result.status, TaskStatus.FAILED, "status");
        h.assertValueEqual(result.summary, "I have no food that minecraft:sheep eats", "summary");
        h.succeed();
    }

    @GameTest
    public void breedNotEnoughAdults(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        h.spawn(EntityTypes.RABBIT, 5, 2, 4);
        Rabbit baby = h.spawn(EntityTypes.RABBIT, 7, 2, 4);
        baby.setAge(-24000);
        c.inventory().addItem(new ItemStack(Items.DANDELION, 2));
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        runner.start(new BreedTask(EntityTypes.RABBIT, 1), "breed_animals");
        runner.tick();
        h.assertValueEqual(result.status, TaskStatus.FAILED, "status");
        h.assertValueEqual(result.summary, "not enough adult minecraft:rabbit ready to breed nearby", "summary");
        h.succeed();
    }
}
