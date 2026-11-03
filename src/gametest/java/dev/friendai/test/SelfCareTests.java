package dev.friendai.test;

import com.google.gson.JsonObject;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.execution.queue.FailurePolicy;
import dev.friendai.execution.queue.GuardAction;
import dev.friendai.execution.queue.QueuedStep;
import dev.friendai.features.combat.minecraft.CombatController;
import dev.friendai.server.execution.ExecutionCoordinator;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.Blocks;

/** The companion spending its own survival items: the water drop, milk, a golden apple and a splash potion. */
public class SelfCareTests {

    @GameTest(maxTicks = 300)
    public void fallsIntoItsOwnWaterAndTakesTheBucketBack(GameTestHelper h) {
        tower(h, 300, 10, 18);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 4, 316, 4);
        c.inventory().addItem(new ItemStack(Items.WATER_BUCKET));
        h.succeedWhen(() -> {
            h.assertTrue(c.onGround(), "still falling after the arena was filled");
            h.assertValueEqual(c.getHealth(), c.getMaxHealth(), "the fall was not broken by the water bucket");
            h.assertValueEqual(
                    c.inventory().countItem(Items.WATER_BUCKET), 1, "the bucket did not come back after the landing");
            BlockPos feet = c.blockPosition();
            h.assertTrue(
                    h.getLevel().getFluidState(feet).isEmpty()
                            && h.getLevel().getFluidState(feet.below()).isEmpty(),
                    "water was left behind at the landing: " + h.getLevel().getBlockState(feet));
        });
    }

    @GameTest(maxTicks = 300)
    public void milkClearsAHarmfulEffectAndTheBeneficialOneToo(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.addEffect(new MobEffectInstance(MobEffects.POISON, 600, 0));
        c.addEffect(new MobEffectInstance(MobEffects.SPEED, 600, 1));
        c.inventory().addItem(new ItemStack(Items.MILK_BUCKET));
        h.succeedWhen(() -> {
            h.assertValueEqual(c.inventory().countItem(Items.MILK_BUCKET), 0, "the milk was never drunk");
            h.assertTrue(!c.hasEffect(MobEffects.POISON), "the poison is still there");
            h.assertTrue(!c.hasEffect(MobEffects.SPEED), "milk must clear a beneficial effect as well");
        });
    }

    @GameTest(maxTicks = 300)
    public void criticalOutsideCombatEatsAGoldenApple(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.hunger().load(6, 0.0F, 0.0F);
        c.setHealth(3.0F);
        c.inventory().addItem(new ItemStack(Items.GOLDEN_APPLE));
        h.succeedWhen(() -> {
            h.assertValueEqual(c.inventory().countItem(Items.GOLDEN_APPLE), 0, "the apple was never eaten");
            h.assertTrue(c.hasEffect(MobEffects.ABSORPTION), "no absorption: the apple's effects were not applied");
            h.assertValueEqual(c.hunger().food(), 10, "the apple's nutrition was not credited to the hunger model");
        });
    }

    /** The reflex holds the runner while it eats, so a task cannot take the hand back mid-use. */
    @GameTest(maxTicks = 200)
    public void eatingHoldsTheRunnerSoATaskCannotTakeTheHandBack(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ExecutionCoordinator runner = c.executionCoordinator();
        runner.resume();
        AtomicInteger taskTicks = new AtomicInteger();
        runner.enqueue(
                new QueuedStep(
                        "hold_probe",
                        new JsonObject(),
                        null,
                        List.of(),
                        GuardAction.WAIT,
                        FailurePolicy.ASK,
                        1,
                        List.of(),
                        null,
                        false),
                ctx -> new Task() {
                    @Override
                    public String type() {
                        return "hold_probe";
                    }

                    @Override
                    public void start(TaskContext context) {}

                    @Override
                    public TaskStatus tick(TaskContext context) {
                        taskTicks.incrementAndGet();
                        return TaskStatus.RUNNING;
                    }

                    @Override
                    public void stop(TaskContext context) {}

                    @Override
                    public String describe() {
                        return "hold probe";
                    }

                    @Override
                    public String resultSummary() {
                        return "";
                    }

                    @Override
                    public boolean hasTimeout() {
                        return false;
                    }

                    @Override
                    public boolean emitsEvents() {
                        return false;
                    }
                });
        c.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_PICKAXE));
        c.inventory().addItem(new ItemStack(Items.BREAD));
        h.runAfterDelay(20, () -> {
            h.assertTrue(taskTicks.get() > 0, "the probe task never ran");
            c.hunger().load(6, 0.0F, 0.0F);
            h.runAfterDelay(10, () -> {
                h.assertTrue(
                        c.combatController().state() == CombatController.State.EATING,
                        "the reflex did not start eating: "
                                + c.combatController().state());
                int frozen = taskTicks.get();
                h.runAfterDelay(6, () -> {
                    h.assertTrue(
                            c.combatController().state() == CombatController.State.EATING,
                            "eating ended before the hold could be observed");
                    h.assertValueEqual(taskTicks.get(), frozen, "the task ticked while the reflex held the runner");
                    h.assertValueEqual(
                            c.inventory().countItem(Items.DIAMOND_PICKAXE),
                            1,
                            "the pickaxe left the inventory while the reflex ate");
                    h.succeed();
                });
            });
        });
    }

    /**
     * The combat reflex eats by swapping the food into the main hand; the pickaxe it displaces belongs to the
     * inventory while the use runs, so nothing disappears and the tool selection of a running task cannot empty the
     * hand and have the reflex credit food that was never eaten.
     */
    @GameTest(maxTicks = 200)
    public void combatEatingKeepsTheDisplacedPickaxeInTheInventory(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_PICKAXE));
        c.inventory().addItem(new ItemStack(Items.BREAD));
        c.hunger().load(6, 0.0F, 0.0F);
        int before = c.hunger().food();
        AtomicBoolean eating = new AtomicBoolean();
        h.succeedWhen(() -> {
            if (c.combatController().state() == CombatController.State.EATING) {
                eating.set(true);
                h.assertValueEqual(
                        c.inventory().countItem(Items.DIAMOND_PICKAXE),
                        1,
                        "the displaced pickaxe must wait in the inventory while the reflex eats");
            }
            h.assertTrue(eating.get(), "the combat reflex never started eating");
            h.assertValueEqual(c.inventory().countItem(Items.BREAD), 0, "the bread was not consumed exactly once");
            h.assertTrue(
                    c.getMainHandItem().is(Items.DIAMOND_PICKAXE),
                    "the pickaxe is back in the hand: " + c.getMainHandItem());
            h.assertTrue(c.hunger().food() > before, "the bread's nutrition was not credited");
        });
    }

    @GameTest(maxTicks = 300)
    public void oneItemPerEmergency(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.hunger().load(6, 0.0F, 0.0F);
        c.setHealth(3.0F);
        c.inventory().addItem(new ItemStack(Items.GOLDEN_APPLE));
        c.inventory().addItem(PotionContents.createItemStack(Items.POTION, Potions.HEALING));
        h.succeedWhen(() -> {
            h.assertValueEqual(c.inventory().countItem(Items.GOLDEN_APPLE), 0, "the apple was not used");
            h.assertValueEqual(
                    c.inventory().countItem(Items.POTION), 1, "a second item was spent on the same emergency");
        });
    }

    @GameTest(maxTicks = 300)
    public void splashPotionHealsAWoundedOwner(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 4, 2, 2);
        c.setOwner(owner.getUUID());
        owner.setHealth(8.0F);
        c.inventory().addItem(PotionContents.createItemStack(Items.SPLASH_POTION, Potions.HEALING));
        h.succeedWhen(() -> {
            h.assertValueEqual(c.inventory().countItem(Items.SPLASH_POTION), 0, "the splash potion was never thrown");
            h.assertTrue(owner.getHealth() > 8.0F, "the owner was not healed: " + owner.getHealth());
        });
    }

    @GameTest(maxTicks = 300)
    public void nothingIsSpentWhenNothingMatches(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.MILK_BUCKET));
        c.inventory().addItem(new ItemStack(Items.GOLDEN_APPLE));
        c.inventory().addItem(new ItemStack(Items.WATER_BUCKET));
        h.runAfterDelay(150, () -> {
            h.assertValueEqual(c.inventory().countItem(Items.MILK_BUCKET), 1, "milk was spent on a healthy body");
            h.assertValueEqual(c.inventory().countItem(Items.GOLDEN_APPLE), 1, "an apple was spent on a healthy body");
            h.assertValueEqual(c.inventory().countItem(Items.WATER_BUCKET), 1, "a bucket was spent on the ground");
            h.succeed();
        });
    }

    /** A stone floor at {@code y} with {@code clearance} blocks of air above it, force-loaded for entities. */
    private static void tower(GameTestHelper h, int y, int size, int clearance) {
        GameTestSupport.keepLoaded(h, -4, -4, size + 4, size + 4);
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                h.setBlock(x, y, z, Blocks.STONE);
                for (int level = y + 1; level <= y + clearance; level++) {
                    h.setBlock(x, level, z, Blocks.AIR);
                }
            }
        }
    }
}
