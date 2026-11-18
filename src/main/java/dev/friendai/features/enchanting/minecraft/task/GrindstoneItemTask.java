package dev.friendai.features.enchanting.minecraft.task;

import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.interact.MenuBlockTask;
import dev.friendai.task.inventory.InventoryOps;
import dev.friendai.task.inventory.ItemMatcher;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Strips the enchantments from an item (or merges two items) at a grindstone and keeps the experience it yields. */
public final class GrindstoneItemTask extends MenuBlockTask {
    private static final int FIRST_SLOT = 0;
    private static final int SECOND_SLOT = 1;
    private static final int RESULT_SLOT = 2;

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
    protected TaskStatus operate(TaskContext c, BlockPos pos) {
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
            if (other.isEmpty()) {
                summary = "I don't have a separate " + second.label() + " for the second input";
                return TaskStatus.FAILED;
            }
            menu.getSlot(SECOND_SLOT).set(other.getFirst());
        }
        ItemStack result = menu.getSlot(RESULT_SLOT).getItem();
        if (result.isEmpty()) {
            summary = "the grindstone has nothing to remove from " + item.label()
                    + (second == null ? "" : " and " + second.label());
            return TaskStatus.FAILED;
        }
        // Vanilla awards at the center of the block and can merge the award into an existing orb in this box.
        AABB around = AABB.ofSize(Vec3.atCenterOf(pos), 1, 1, 1);
        Map<ExperienceOrb, Integer> before = new HashMap<>();
        for (ExperienceOrb orb : c.level().getEntitiesOfClass(ExperienceOrb.class, around)) {
            before.put(orb, orbCount(c, orb));
        }
        ItemStack collected = menu.quickMoveStack(player, RESULT_SLOT);
        if (collected.isEmpty()) {
            summary = "the grindstone result could not be collected";
            return TaskStatus.FAILED;
        }

        int collectedXp = 0;
        int mergedXp = 0;
        for (ExperienceOrb orb : c.level().getEntitiesOfClass(ExperienceOrb.class, around)) {
            int oldCount = before.getOrDefault(orb, 0);
            int award = (orbCount(c, orb) - oldCount) * orb.getValue();
            if (oldCount == 0) {
                collectedXp += award;
                orb.discard();
            } else if (award > 0) {
                mergedXp += award;
            }
        }
        int beforePoints = companion.experiencePoints();
        // Through the fake player: closing the menu copies its experience back and would drop a direct addition.
        player.giveExperiencePoints(collectedXp);
        companion.hands().closeMenu();
        int gained = companion.experiencePoints() - beforePoints;
        summary = "ground " + item.label() + " into " + BuiltInRegistries.ITEM.getKey(collected.getItem())
                + " and gained " + gained + " experience points"
                + (mergedXp == 0 ? "" : "; " + mergedXp + " experience points remain in orbs at the grindstone");
        return TaskStatus.SUCCESS;
    }

    /** Orb entities can represent multiple equal-value awards; their public value is for one award. */
    private static int orbCount(TaskContext c, ExperienceOrb orb) {
        TagValueOutput output = TagValueOutput.createWithContext(
                ProblemReporter.DISCARDING, c.level().registryAccess());
        orb.saveWithoutId(output);
        return output.buildResult().getIntOr("Count", 1);
    }
}
