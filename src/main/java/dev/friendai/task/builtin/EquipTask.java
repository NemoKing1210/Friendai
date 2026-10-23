package dev.friendai.task.builtin;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.combat.Equipment;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;
import org.jetbrains.annotations.Nullable;

/** Wears or holds an item from the inventory. Finishes in one tick. */
public final class EquipTask implements Task {
    private final Item item;
    private final EquipmentSlot slot;
    private String summary = "";

    /** @param slot the target slot, or null to use the item's own equipment slot (main hand for other items) */
    public EquipTask(Item item, @Nullable EquipmentSlot slot) {
        this.item = item;
        this.slot = slot;
    }

    @Override
    public String type() {
        return "equip_item";
    }

    @Override
    public void start(TaskContext c) {}

    @Override
    public TaskStatus tick(TaskContext c) {
        CompanionEntity companion = c.companion();
        String id = BuiltInRegistries.ITEM.getKey(item).toString();
        int found = -1;
        for (int i = 0; i < companion.inventory().getContainerSize(); i++) {
            if (companion.inventory().getItem(i).is(item)) {
                found = i;
                break;
            }
        }
        if (found < 0) {
            summary = "I don't have " + id;
            return TaskStatus.FAILED;
        }
        ItemStack stack = companion.inventory().getItem(found);
        Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
        EquipmentSlot target = slot != null ? slot : equippable != null ? equippable.slot() : EquipmentSlot.MAINHAND;
        if ((equippable != null && equippable.slot() != target) || (target.isArmor() && equippable == null)) {
            summary = id + " can't be worn on " + target.getSerializedName();
            return TaskStatus.FAILED;
        }
        ItemStack moved = companion.inventory().removeItemNoUpdate(found);
        ItemStack previous = companion.getItemBySlot(target);
        companion.setItemSlot(target, moved);
        if (!previous.isEmpty()) {
            ItemStack leftover = companion.inventory().addItem(previous);
            if (!leftover.isEmpty() && companion.level() instanceof ServerLevel level) {
                companion.spawnAtLocation(level, leftover);
            }
        }
        if (target.isArmor()) {
            Equipment.equipBestArmor(companion);
        }
        summary = (target.isArmor() ? "now wearing " : "now holding ")
                + id
                + (target == EquipmentSlot.OFFHAND ? " in the offhand" : "");
        return TaskStatus.SUCCESS;
    }

    @Override
    public void stop(TaskContext c) {
        c.companion().getNavigation().stop();
    }

    @Override
    public String describe() {
        return "equipping " + BuiltInRegistries.ITEM.getKey(item);
    }

    @Override
    public String resultSummary() {
        return summary;
    }
}
