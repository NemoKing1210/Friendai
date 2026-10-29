package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.features.enchanting.minecraft.task.GrindstoneItemTask;
import dev.friendai.server.execution.ExecutionCoordinator;
import dev.friendai.task.TaskListener;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.inventory.ItemMatcher;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;

/** The companion strips enchantments at a grindstone and keeps the experience that comes out. */
public class GrindstoneSkillTests {
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

    private static CompanionEntity setup(GameTestHelper h, int bandY, boolean grindstone) {
        GameTestSupport.platformAt(h, bandY, 12);
        if (grindstone) {
            h.setBlock(3, bandY + 1, 2, Blocks.GRINDSTONE);
        }
        return h.spawn(FriendaiEntities.COMPANION, 2, bandY + 1, 2);
    }

    private static ItemMatcher match(Item item) {
        return ItemMatcher.parse(BuiltInRegistries.ITEM.getKey(item).toString()).orElseThrow();
    }

    private static ItemStack find(CompanionEntity c, Item item) {
        for (int i = 0; i < c.inventory().getContainerSize(); i++) {
            if (c.inventory().getItem(i).is(item)) {
                return c.inventory().getItem(i);
            }
        }
        return ItemStack.EMPTY;
    }

    @GameTest
    public void stripsEnchantsAndGivesExperience(GameTestHelper h) {
        CompanionEntity c = setup(h, 144, true);
        var sharpness = h.getLevel()
                .registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.SHARPNESS);
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        EnchantmentHelper.updateEnchantments(sword, mutable -> mutable.set(sharpness, 3));
        c.inventory().addItem(sword);
        Result result = new Result();
        ExecutionCoordinator runner = new ExecutionCoordinator(c, () -> 300, () -> false);
        runner.setListener(result);
        runner.start(new GrindstoneItemTask(match(Items.DIAMOND_SWORD), null, null), "grindstone_item");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            ItemStack back = find(c, Items.DIAMOND_SWORD);
            h.assertTrue(!back.isEmpty(), "the sword did not come back");
            h.assertTrue(!EnchantmentHelper.hasAnyEnchantments(back), "the sword is still enchanted");
            h.assertTrue(c.experience().points() > 0, "no experience was gained");
        });
    }

    @GameTest
    public void failsOnPlainItem(GameTestHelper h) {
        CompanionEntity c = setup(h, 148, true);
        c.inventory().addItem(new ItemStack(Items.STICK));
        Result result = new Result();
        ExecutionCoordinator runner = new ExecutionCoordinator(c, () -> 300, () -> false);
        runner.setListener(result);
        runner.start(new GrindstoneItemTask(match(Items.STICK), null, null), "grindstone_item");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.FAILED, "status was " + result.status + " " + result.summary);
            h.assertTrue(!find(c, Items.STICK).isEmpty(), "the stick must come back");
            h.assertTrue(
                    c.experience().points() == 0,
                    "points were " + c.experience().points());
        });
    }

    @GameTest
    public void failsWithoutGrindstone(GameTestHelper h) {
        CompanionEntity c = setup(h, 156, false);
        c.inventory().addItem(new ItemStack(Items.DIAMOND_SWORD));
        BlockPos nowhere = h.absolutePos(new BlockPos(6, 157, 6));
        Result result = new Result();
        ExecutionCoordinator runner = new ExecutionCoordinator(c, () -> 300, () -> false);
        runner.setListener(result);
        runner.start(new GrindstoneItemTask(match(Items.DIAMOND_SWORD), null, nowhere), "grindstone_item");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.FAILED, "status was " + result.status + " " + result.summary);
            h.assertTrue(result.summary.contains("grindstone"), "summary was " + result.summary);
            h.assertTrue(!find(c, Items.DIAMOND_SWORD).isEmpty(), "the sword must stay");
        });
    }
}
