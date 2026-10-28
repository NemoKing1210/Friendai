package dev.friendai.task.builtin;

import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.inventory.InventoryOps;
import dev.friendai.task.inventory.ItemMatcher;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import org.jetbrains.annotations.Nullable;

/** Repairs, combines or renames items at an anvil, paying the vanilla experience cost from the companion's levels. */
public final class AnvilItemTask extends MenuBlockTask {
    private static final int LEFT_SLOT = 0;
    private static final int RIGHT_SLOT = 1;
    private static final int RESULT_SLOT = 2;

    private final ItemMatcher left;
    private final @Nullable ItemMatcher right;
    private final @Nullable String rename;

    public AnvilItemTask(
            ItemMatcher left, @Nullable ItemMatcher right, @Nullable String rename, @Nullable BlockPos anvil) {
        super(Blocks.ANVIL, "anvil", anvil);
        if (left == null) {
            throw new IllegalArgumentException("anvil item must not be empty");
        }
        if (right == null && (rename == null || rename.isBlank())) {
            throw new IllegalArgumentException("give a second item or a new name");
        }
        this.left = left;
        this.right = right;
        this.rename = rename == null || rename.isBlank() ? null : rename;
    }

    @Override
    public String type() {
        return "anvil_item";
    }

    @Override
    public String describe() {
        return "working " + left.label() + (right == null ? "" : " with " + right.label()) + " at an anvil";
    }

    @Override
    TaskStatus operate(TaskContext c, BlockPos pos) {
        var companion = c.companion();
        int needLeft = 1;
        if (right != null && right.label().equals(left.label())) {
            needLeft = 2;
        }
        if (InventoryOps.count(companion.inventory(), left) < needLeft) {
            summary = "I don't have " + (needLeft == 2 ? "two " : "") + left.label();
            return TaskStatus.FAILED;
        }
        if (right != null && InventoryOps.count(companion.inventory(), right) < 1) {
            summary = "I don't have " + right.label();
            return TaskStatus.FAILED;
        }
        if (!(companion.hands().openMenu(pos) instanceof AnvilMenu menu)) {
            summary = "the anvil at " + coords(pos) + " would not open";
            return TaskStatus.FAILED;
        }
        ServerPlayer player = companion.hands().player();

        List<ItemStack> first = InventoryOps.remove(companion.inventory(), left, 1);
        menu.getSlot(LEFT_SLOT).set(first.get(0));
        if (right != null) {
            List<ItemStack> second = InventoryOps.remove(companion.inventory(), right, 1);
            menu.getSlot(RIGHT_SLOT).set(second.get(0));
        }
        if (rename != null) {
            menu.setItemName(rename);
        }

        ItemStack result = menu.getSlot(RESULT_SLOT).getItem();
        if (result.isEmpty()) {
            summary =
                    "the anvil can't do anything with " + left.label() + (right == null ? "" : " and " + right.label());
            return TaskStatus.FAILED;
        }
        int cost = menu.getCost();
        if (!menu.getSlot(RESULT_SLOT).mayPickup(player)) {
            summary = player.experienceLevel < cost
                    ? "that needs " + cost + " experience levels; I have " + player.experienceLevel
                    : "the anvil refused it (too expensive)";
            return TaskStatus.FAILED;
        }
        menu.quickMoveStack(player, RESULT_SLOT);
        summary = "anvil made " + BuiltInRegistries.ITEM.getKey(result.getItem()) + " for " + cost + " level"
                + (cost == 1 ? "" : "s");
        return TaskStatus.SUCCESS;
    }
}
