package dev.friendai.task.builtin;

import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.inventory.InventoryOps;
import dev.friendai.task.inventory.ItemMatcher;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.Blocks;
import org.jetbrains.annotations.Nullable;

/** Enchants one item at an enchanting table, paying lapis and experience levels like a player would. */
public final class EnchantItemTask extends MenuBlockTask {
    private static final int ITEM_SLOT = 0;
    private static final int LAPIS_SLOT = 1;

    private final ItemMatcher item;
    private final int option;

    public EnchantItemTask(ItemMatcher item, int option, @Nullable BlockPos table) {
        super(Blocks.ENCHANTING_TABLE, "enchanting table", table);
        if (item == null) {
            throw new IllegalArgumentException("enchant item must not be empty");
        }
        if (option < 1 || option > 3) {
            throw new IllegalArgumentException("option must be 1, 2 or 3: " + option);
        }
        this.item = item;
        this.option = option;
    }

    @Override
    public String type() {
        return "enchant_item";
    }

    @Override
    public String describe() {
        return "enchanting " + item.label() + " (option " + option + ")";
    }

    @Override
    TaskStatus operate(TaskContext c, BlockPos pos) {
        var companion = c.companion();
        if (InventoryOps.count(companion.inventory(), item) < 1) {
            summary = "I don't have " + item.label();
            return TaskStatus.FAILED;
        }
        if (InventoryOps.count(companion.inventory(), LAPIS) < option) {
            summary = "I need " + option + " lapis lazuli for option " + option;
            return TaskStatus.FAILED;
        }
        if (!(companion.hands().openMenu(pos) instanceof EnchantmentMenu menu)) {
            summary = "the enchanting table at " + coords(pos) + " would not open";
            return TaskStatus.FAILED;
        }
        ServerPlayer player = companion.hands().player();

        List<ItemStack> taken = InventoryOps.remove(companion.inventory(), item, 1);
        menu.getSlot(ITEM_SLOT).set(taken.get(0));
        List<ItemStack> lapis = InventoryOps.remove(companion.inventory(), LAPIS, option);
        int lapisCount = 0;
        for (ItemStack stack : lapis) {
            lapisCount += stack.getCount();
        }
        menu.getSlot(LAPIS_SLOT).set(new ItemStack(Items.LAPIS_LAZULI, lapisCount));

        int cost = menu.costs[option - 1];
        if (cost <= 0 || menu.enchantClue[option - 1] < 0) {
            summary = describe(taken.get(0)) + " can't be enchanted (it may already be enchanted)";
            return TaskStatus.FAILED;
        }
        if (player.experienceLevel < option || player.experienceLevel < cost) {
            summary = "option " + option + " needs " + Math.max(cost, option) + " experience levels; I have "
                    + player.experienceLevel;
            return TaskStatus.FAILED;
        }
        if (!menu.clickMenuButton(player, option - 1)) {
            summary = "the enchanting table refused option " + option;
            return TaskStatus.FAILED;
        }
        ItemStack result = menu.getSlot(ITEM_SLOT).getItem();
        summary = "enchanted " + describe(result) + " for " + option + " level" + (option == 1 ? "" : "s") + " and "
                + option + " lapis: " + enchantmentList(result);
        return TaskStatus.SUCCESS;
    }

    private static final ItemMatcher LAPIS =
            ItemMatcher.parse("minecraft:lapis_lazuli").orElseThrow();

    private static String describe(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static String enchantmentList(ItemStack stack) {
        List<String> names = new ArrayList<>();
        EnchantmentHelper.getEnchantmentsForCrafting(stack)
                .entrySet()
                .forEach(entry -> names.add(entry.getKey().getRegisteredName() + " " + entry.getIntValue()));
        return names.isEmpty() ? "no enchantments" : String.join(", ", names);
    }
}
