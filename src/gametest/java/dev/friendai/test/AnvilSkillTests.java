package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.survival.ExperienceModel;
import dev.friendai.task.TaskListener;
import dev.friendai.task.TaskRunner;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.builtin.AnvilItemTask;
import dev.friendai.task.inventory.ItemMatcher;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;

/** The companion repairs, combines and renames items at an anvil with its own experience levels. */
public class AnvilSkillTests {
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

    private static CompanionEntity setup(GameTestHelper h, int bandY, boolean anvil) {
        GameTestSupport.platformAt(h, bandY, 12);
        if (anvil) {
            h.setBlock(3, bandY + 1, 2, Blocks.ANVIL);
        }
        return h.spawn(FriendaiEntities.COMPANION, 2, bandY + 1, 2);
    }

    private static void levels(CompanionEntity c, int level) {
        c.experience().load(ExperienceModel.pointsForLevel(level));
        c.syncExperience();
    }

    private static ItemMatcher match(Item item) {
        return ItemMatcher.parse(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString())
                .orElseThrow();
    }

    private static int count(CompanionEntity c, Item item) {
        int total = 0;
        for (int i = 0; i < c.inventory().getContainerSize(); i++) {
            if (c.inventory().getItem(i).is(item)) {
                total += c.inventory().getItem(i).getCount();
            }
        }
        return total;
    }

    private static ItemStack find(CompanionEntity c, Item item) {
        for (int i = 0; i < c.inventory().getContainerSize(); i++) {
            if (c.inventory().getItem(i).is(item)) {
                return c.inventory().getItem(i);
            }
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack sharpnessBook(GameTestHelper h, int level) {
        var holder = h.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.SHARPNESS);
        ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
        EnchantmentHelper.updateEnchantments(book, mutable -> mutable.set(holder, level));
        return book;
    }

    private static void succeeds(GameTestHelper h, CompanionEntity c, AnvilItemTask task, Runnable check) {
        Result result = new Result();
        TaskRunner runner = new TaskRunner(c, () -> 300, () -> false);
        runner.setListener(result);
        runner.start(task, "anvil_item");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            check.run();
        });
    }

    private static void fails(GameTestHelper h, CompanionEntity c, AnvilItemTask task, String expected,
            Runnable check) {
        Result result = new Result();
        TaskRunner runner = new TaskRunner(c, () -> 300, () -> false);
        runner.setListener(result);
        runner.start(task, "anvil_item");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.FAILED, "status was " + result.status + " " + result.summary);
            h.assertTrue(result.summary.contains(expected), "summary was " + result.summary);
            check.run();
        });
    }

    @GameTest
    public void repairsWithMaterial(GameTestHelper h) {
        CompanionEntity c = setup(h, 92, true);
        ItemStack pickaxe = new ItemStack(Items.IRON_PICKAXE);
        pickaxe.setDamageValue(100);
        c.inventory().addItem(pickaxe);
        c.inventory().addItem(new ItemStack(Items.IRON_INGOT, 2));
        levels(c, 30);
        succeeds(h, c, new AnvilItemTask(match(Items.IRON_PICKAXE), match(Items.IRON_INGOT), null, null), () -> {
            h.assertTrue(find(c, Items.IRON_PICKAXE).getDamageValue() < 100, "the pickaxe was not repaired");
            h.assertTrue(c.experience().level() < 30, "levels were not spent: " + c.experience().level());
            h.assertTrue(count(c, Items.IRON_INGOT) < 2, "no ingot was used");
        });
    }

    @GameTest
    public void combinesTwoBooks(GameTestHelper h) {
        CompanionEntity c = setup(h, 96, true);
        c.inventory().addItem(sharpnessBook(h, 1));
        c.inventory().addItem(sharpnessBook(h, 1));
        levels(c, 30);
        succeeds(h, c, new AnvilItemTask(match(Items.ENCHANTED_BOOK), match(Items.ENCHANTED_BOOK), null, null), () -> {
            h.assertTrue(count(c, Items.ENCHANTED_BOOK) == 1, "books left " + count(c, Items.ENCHANTED_BOOK));
            h.assertTrue(c.experience().level() < 30, "levels were not spent");
        });
    }

    @GameTest
    public void renames(GameTestHelper h) {
        CompanionEntity c = setup(h, 120, true);
        c.inventory().addItem(new ItemStack(Items.IRON_SWORD));
        levels(c, 30);
        succeeds(h, c, new AnvilItemTask(match(Items.IRON_SWORD), null, "Excalibur", null), () -> {
            var name = find(c, Items.IRON_SWORD).get(DataComponents.CUSTOM_NAME);
            h.assertTrue(name != null && name.getString().equals("Excalibur"), "name was " + name);
            h.assertTrue(c.experience().level() == 29, "level was " + c.experience().level());
        });
    }

    @GameTest
    public void failsWhenTooExpensive(GameTestHelper h) {
        CompanionEntity c = setup(h, 124, true);
        c.inventory().addItem(new ItemStack(Items.IRON_SWORD));
        fails(h, c, new AnvilItemTask(match(Items.IRON_SWORD), null, "Excalibur", null), "level", () -> {
            h.assertTrue(find(c, Items.IRON_SWORD).get(DataComponents.CUSTOM_NAME) == null, "the sword was renamed");
            h.assertTrue(count(c, Items.IRON_SWORD) == 1, "the sword must stay");
        });
    }

    @GameTest
    public void failsWithNoResult(GameTestHelper h) {
        CompanionEntity c = setup(h, 132, true);
        c.inventory().addItem(new ItemStack(Items.IRON_SWORD));
        c.inventory().addItem(new ItemStack(Items.DIRT));
        levels(c, 30);
        fails(h, c, new AnvilItemTask(match(Items.IRON_SWORD), match(Items.DIRT), null, null), "anvil", () -> {
            h.assertTrue(count(c, Items.IRON_SWORD) == 1, "the sword must come back");
            h.assertTrue(count(c, Items.DIRT) == 1, "the dirt must come back");
            h.assertTrue(c.experience().level() == 30, "levels must be untouched");
        });
    }

    @GameTest
    public void failsWithoutAnvil(GameTestHelper h) {
        CompanionEntity c = setup(h, 136, false);
        c.inventory().addItem(new ItemStack(Items.IRON_SWORD));
        levels(c, 30);
        BlockPos nowhere = h.absolutePos(new BlockPos(6, 137, 6));
        fails(h, c, new AnvilItemTask(match(Items.IRON_SWORD), null, "Excalibur", nowhere), "anvil", () -> {
            h.assertTrue(count(c, Items.IRON_SWORD) == 1, "the sword must stay");
        });
    }
}
