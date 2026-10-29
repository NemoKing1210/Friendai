package dev.friendai.features.enchanting.minecraft.task;

import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.inventory.InventoryOps;
import dev.friendai.task.inventory.ItemMatcher;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

/** Strips the enchantments from an item (or merges two items) at a grindstone and keeps the experience it yields. */
public final class GrindstoneItemTask extends MenuBlockTask {
    private static final int FIRST_SLOT = 0;
    private static final int SECOND_SLOT = 1;
    private static final int RESULT_SLOT = 2;

    /** How far from the grindstone the released experience orbs are collected. */
    private static final double ORB_RADIUS = 3.0;

    private final ItemMatcher item;
    private final @Nullable ItemMatcher second;

    public GrindstoneItemTask(ItemMatcher item, @Nullable ItemMatcher second, @Nullable BlockPos grindstone) {
        super(Blocks.GRINDSTONE, "grindstone", grindstone);
        if (item == null) {
            throw new IllegalArgumentException("grindstone item must not be empty");
        }
        this.item = item;
        this.second = second;
    }

    @Override
    public String type() {
        return "grindstone_item";
    }

    @Override
    public String describe() {
        return "grinding " + item.label() + (second == null ? "" : " with " + second.label());
    }

    @Override
    TaskStatus operate(TaskContext c, BlockPos pos) {
        var companion = c.companion();
        int needFirst = second != null && second.label().equals(item.label()) ? 2 : 1;
        if (InventoryOps.count(companion.inventory(), item) < needFirst) {
            summary = "I don't have " + (needFirst == 2 ? "two " : "") + item.label();
            return TaskStatus.FAILED;
        }
        if (second != null && InventoryOps.count(companion.inventory(), second) < 1) {
            summary = "I don't have " + second.label();
            return TaskStatus.FAILED;
        }
        if (!(companion.hands().openMenu(pos) instanceof GrindstoneMenu menu)) {
            summary = "the grindstone at " + coords(pos) + " would not open";
            return TaskStatus.FAILED;
        }
        ServerPlayer player = companion.hands().player();

        List<ItemStack> first = InventoryOps.remove(companion.inventory(), item, 1);
        menu.getSlot(FIRST_SLOT).set(first.get(0));
        if (second != null) {
            List<ItemStack> other = InventoryOps.remove(companion.inventory(), second, 1);
            menu.getSlot(SECOND_SLOT).set(other.get(0));
        }
        ItemStack result = menu.getSlot(RESULT_SLOT).getItem();
        if (result.isEmpty()) {
            summary = "the grindstone has nothing to remove from " + item.label()
                    + (second == null ? "" : " and " + second.label());
            return TaskStatus.FAILED;
        }
        String resultId = BuiltInRegistries.ITEM.getKey(result.getItem()).toString();
        menu.quickMoveStack(player, RESULT_SLOT);

        int gained = 0;
        AABB around = new AABB(pos).inflate(ORB_RADIUS);
        for (ExperienceOrb orb : c.level().getEntitiesOfClass(ExperienceOrb.class, around)) {
            gained += Math.max(0, orb.getValue());
            orb.discard();
        }
        // Through the fake player: closing the menu copies its experience back and would drop a direct addition.
        player.giveExperiencePoints(gained);
        summary = "ground " + item.label() + " into " + resultId + " and gained " + gained + " experience points";
        return TaskStatus.SUCCESS;
    }
}
