package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.survival.ExperienceModel;
import dev.friendai.task.TaskListener;
import dev.friendai.task.TaskRunner;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.builtin.EnchantItemTask;
import dev.friendai.task.inventory.ItemMatcher;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.Blocks;

/** The companion enchants items at an enchanting table with lapis and its own experience levels. */
public class EnchantSkillTests {
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

    private static CompanionEntity setup(GameTestHelper h, int bandY, boolean table) {
        GameTestSupport.platformAt(h, bandY, 12);
        if (table) {
            h.setBlock(3, bandY + 1, 2, Blocks.ENCHANTING_TABLE);
        }
        return h.spawn(FriendaiEntities.COMPANION, 2, bandY + 1, 2);
    }

    private static void levels(CompanionEntity c, int level) {
        c.experience().load(ExperienceModel.pointsForLevel(level));
        c.syncExperience();
    }

    private static int count(CompanionEntity c, net.minecraft.world.item.Item item) {
        int total = 0;
        for (int i = 0; i < c.inventory().getContainerSize(); i++) {
            if (c.inventory().getItem(i).is(item)) {
                total += c.inventory().getItem(i).getCount();
            }
        }
        return total;
    }

    @GameTest
    public void enchantsWithLapisAndLevels(GameTestHelper h) {
        CompanionEntity c = setup(h, 64, true);
        c.inventory().addItem(new ItemStack(Items.IRON_SWORD));
        c.inventory().addItem(new ItemStack(Items.LAPIS_LAZULI, 3));
        levels(c, 30);
        Result result = new Result();
        TaskRunner runner = new TaskRunner(c, () -> 300, () -> false);
        runner.setListener(result);
        runner.start(
                new EnchantItemTask(ItemMatcher.parse("minecraft:iron_sword").orElseThrow(), 1, null), "enchant_item");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            ItemStack sword = ItemStack.EMPTY;
            for (int i = 0; i < c.inventory().getContainerSize(); i++) {
                if (c.inventory().getItem(i).is(Items.IRON_SWORD)) {
                    sword = c.inventory().getItem(i);
                }
            }
            h.assertTrue(EnchantmentHelper.hasAnyEnchantments(sword), "the sword was not enchanted");
            h.assertTrue(
                    c.experience().level() == 29, "level was " + c.experience().level());
            h.assertTrue(count(c, Items.LAPIS_LAZULI) == 2, "lapis left " + count(c, Items.LAPIS_LAZULI));
        });
    }

    private static void failing(
            GameTestHelper h, CompanionEntity c, EnchantItemTask task, String expected, Runnable extra) {
        Result result = new Result();
        TaskRunner runner = new TaskRunner(c, () -> 300, () -> false);
        runner.setListener(result);
        runner.start(task, "enchant_item");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.FAILED, "status was " + result.status + " " + result.summary);
            h.assertTrue(result.summary.contains(expected), "summary was " + result.summary);
            extra.run();
        });
    }

    private static EnchantItemTask swordTask(int option) {
        return new EnchantItemTask(ItemMatcher.parse("minecraft:iron_sword").orElseThrow(), option, null);
    }

    @GameTest
    public void failsWithoutLapis(GameTestHelper h) {
        CompanionEntity c = setup(h, 68, true);
        c.inventory().addItem(new ItemStack(Items.IRON_SWORD));
        levels(c, 30);
        failing(h, c, swordTask(1), "lapis", () -> {
            h.assertTrue(count(c, Items.IRON_SWORD) == 1, "the sword must stay");
            h.assertTrue(
                    c.experience().level() == 30,
                    "levels must be untouched: " + c.experience().level());
        });
    }

    @GameTest
    public void failsWithoutLevels(GameTestHelper h) {
        CompanionEntity c = setup(h, 72, true);
        c.inventory().addItem(new ItemStack(Items.IRON_SWORD));
        c.inventory().addItem(new ItemStack(Items.LAPIS_LAZULI, 3));
        failing(h, c, swordTask(1), "level", () -> {
            h.assertTrue(count(c, Items.LAPIS_LAZULI) == 3, "lapis must stay");
            h.assertTrue(count(c, Items.IRON_SWORD) == 1, "the sword must stay");
        });
    }

    @GameTest
    public void failsWithoutItem(GameTestHelper h) {
        CompanionEntity c = setup(h, 76, true);
        c.inventory().addItem(new ItemStack(Items.LAPIS_LAZULI, 3));
        levels(c, 30);
        failing(h, c, swordTask(1), "iron_sword", () -> {
            h.assertTrue(count(c, Items.LAPIS_LAZULI) == 3, "lapis must stay");
            h.assertTrue(c.experience().level() == 30, "levels must be untouched");
        });
    }

    @GameTest
    public void failsWhenNoTableNearby(GameTestHelper h) {
        CompanionEntity c = setup(h, 84, false);
        c.inventory().addItem(new ItemStack(Items.IRON_SWORD));
        c.inventory().addItem(new ItemStack(Items.LAPIS_LAZULI, 3));
        levels(c, 30);
        BlockPos nowhere = h.absolutePos(new BlockPos(6, 85, 6));
        failing(
                h,
                c,
                new EnchantItemTask(ItemMatcher.parse("minecraft:iron_sword").orElseThrow(), 1, nowhere),
                "enchanting table",
                () -> {
                    h.assertTrue(count(c, Items.LAPIS_LAZULI) == 3, "lapis must stay");
                });
    }

    @GameTest
    public void failsOnProtectedTable(GameTestHelper h) {
        CompanionEntity c = setup(h, 88, true);
        c.inventory().addItem(new ItemStack(Items.IRON_SWORD));
        c.inventory().addItem(new ItemStack(Items.LAPIS_LAZULI, 3));
        levels(c, 30);
        BlockPos table = h.absolutePos(new BlockPos(3, 89, 2));
        c.hands().overrideProtection(p -> p.equals(table));
        failing(
                h,
                c,
                new EnchantItemTask(ItemMatcher.parse("minecraft:iron_sword").orElseThrow(), 1, table),
                "protected",
                () -> {
                    h.assertTrue(count(c, Items.LAPIS_LAZULI) == 3, "lapis must stay");
                });
    }
}
