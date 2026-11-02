package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.features.brewing.minecraft.task.BrewCheckpoints;
import dev.friendai.features.brewing.minecraft.task.BrewTask;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskState;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.inventory.InventoryOps;
import dev.friendai.task.inventory.ItemMatcher;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.Blocks;

/** Brewing through the real stand menu: a supported chain, a resumed stand and a typed missing-input refusal. */
public class BrewingTests {
    @GameTest(maxTicks = 1600)
    public void brewsStrengthAtAFoundStand(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        BlockPos stand = new BlockPos(4, 2, 3);
        h.setBlock(stand, Blocks.BREWING_STAND);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(PotionContents.createItemStack(Items.POTION, Potions.WATER));
        c.inventory().addItem(new ItemStack(Items.NETHER_WART, 1));
        c.inventory().addItem(new ItemStack(Items.BLAZE_POWDER, 1));

        BrewTask task = new BrewTask("minecraft:strength", 1, h.absolutePos(stand));
        TaskContext context = new TaskContext(c, h.getLevel());
        task.start(context);

        h.succeedWhen(() -> {
            TaskStatus status = task.tick(context);
            if (status == TaskStatus.RUNNING) {
                return;
            }
            h.assertValueEqual(status, TaskStatus.SUCCESS, "summary=" + task.resultSummary());
            h.assertValueEqual(strengthCount(c), 1, "one strength potion in the inventory");
            h.assertValueEqual(wartCount(c), 0, "the nether wart was consumed");
            h.assertValueEqual(waterCount(c), 0, "the water bottle became the potion");
            BrewTask restored = (BrewTask) BrewCheckpoints.brew(new TaskState("brew_potion", task.saveData()), context)
                    .orElseThrow();
            restored.start(context);
            h.assertValueEqual(
                    restored.tick(context), TaskStatus.SUCCESS, "a completed checkpoint must not brew again");
        });
    }

    @GameTest(maxTicks = 900)
    public void aHalfFinishedStandSkipsTheCompletedStep(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        BlockPos stand = new BlockPos(4, 2, 3);
        h.setBlock(stand, Blocks.BREWING_STAND);
        var entity = (net.minecraft.world.level.block.entity.BrewingStandBlockEntity)
                h.getLevel().getBlockEntity(h.absolutePos(stand));
        entity.setItem(0, PotionContents.createItemStack(Items.POTION, Potions.AWKWARD));
        entity.setItem(4, new ItemStack(Items.BLAZE_POWDER, 1));
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.BLAZE_POWDER, 1));

        BrewTask task = new BrewTask("minecraft:strength", 1, h.absolutePos(stand));
        TaskContext context = new TaskContext(c, h.getLevel());
        task.start(context);

        h.succeedWhen(() -> {
            TaskStatus status = task.tick(context);
            if (status == TaskStatus.RUNNING) {
                return;
            }
            h.assertValueEqual(status, TaskStatus.SUCCESS, "summary=" + task.resultSummary());
            h.assertValueEqual(strengthCount(c), 1, "the awkward potion became strength");
            h.assertTrue(task.resultSummary().contains("blaze powder"), "summary=" + task.resultSummary());
        });
    }

    @GameTest(maxTicks = 400)
    public void aMissingWaterBottleIsOneNamedRefusal(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        BlockPos stand = new BlockPos(4, 2, 3);
        h.setBlock(stand, Blocks.BREWING_STAND);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.NETHER_WART, 1));
        c.inventory().addItem(new ItemStack(Items.BLAZE_POWDER, 1));

        BrewTask task = new BrewTask("minecraft:strength", 1, h.absolutePos(stand));
        TaskContext context = new TaskContext(c, h.getLevel());
        task.start(context);

        h.succeedWhen(() -> {
            TaskStatus status = task.tick(context);
            if (status == TaskStatus.RUNNING) {
                return;
            }
            h.assertValueEqual(status, TaskStatus.FAILED, "summary=" + task.resultSummary());
            h.assertTrue(
                    task.resultSummary().contains("water bottle"),
                    "summary names the missing piece: " + task.resultSummary());
        });
    }

    private static int strengthCount(CompanionEntity c) {
        int count = 0;
        for (int i = 0; i < c.inventory().getContainerSize(); i++) {
            ItemStack stack = c.inventory().getItem(i);
            PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
            if (contents != null && contents.is(Potions.STRENGTH)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static int waterCount(CompanionEntity c) {
        int count = 0;
        for (int i = 0; i < c.inventory().getContainerSize(); i++) {
            ItemStack stack = c.inventory().getItem(i);
            PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
            if (contents != null && contents.is(Potions.WATER)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static int wartCount(CompanionEntity c) {
        return InventoryOps.count(
                c.inventory(), ItemMatcher.parse("minecraft:nether_wart").orElseThrow());
    }
}
