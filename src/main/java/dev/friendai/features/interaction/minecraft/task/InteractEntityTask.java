package dev.friendai.features.interaction.minecraft.task;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.navigation.minecraft.nav.EntityTargets;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.combat.Threats;
import dev.friendai.task.inventory.InventoryOps;
import dev.friendai.task.inventory.ItemMatcher;
import java.util.List;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

/**
 * Right-clicks an animal, with an item from the inventory in hand or with an empty hand: pet it, feed it, name it.
 * Only animals the companion may touch; other players' pets and named mobs stay off limits.
 */
public final class InteractEntityTask extends ReachEntityTask {
    private static final double REACH = 2.5;

    @Nullable
    private final ItemMatcher item;

    public InteractEntityTask(Entity target, @Nullable ItemMatcher item, CompanionEntity companion) {
        super(target, REACH);
        if (target instanceof Piglin && item != null && item.test(new ItemStack(Items.GOLD_INGOT))) {
            throw new IllegalArgumentException(
                    "generic tools cannot initiate piglin barter; a dedicated explicit bounded barter order is required");
        }
        if (!(target instanceof Animal animal)) {
            throw new IllegalArgumentException(
                    "I can only interact with animals, not a " + EntityTargets.label(target));
        }
        if (Threats.isProtected((LivingEntity) animal, companion)) {
            throw new IllegalArgumentException("that " + EntityTargets.label(target) + " is not mine to touch");
        }
        this.item = item;
    }

    @Override
    public String type() {
        return "interact_entity";
    }

    @Override
    TaskStatus reached(TaskContext c) {
        CompanionEntity companion = c.companion();
        ItemStack stack = ItemStack.EMPTY;
        if (item != null) {
            List<ItemStack> taken = InventoryOps.remove(companion.inventory(), item, 1);
            if (taken.isEmpty()) {
                summary = "I don't have " + item.label();
                return TaskStatus.FAILED;
            }
            stack = taken.get(0);
        }
        companion.body().holdLook(target::getEyePosition, 20);
        InteractionResult result = companion.hands().interact(target, stack);
        // The hands copy the stack and return whatever they did not use to the inventory themselves.
        if (!result.consumesAction()) {
            summary = "nothing happened when I used " + (item == null ? "my hand" : item.label()) + " on the "
                    + EntityTargets.label(target);
            return TaskStatus.FAILED;
        }
        summary = "interacted with the " + EntityTargets.label(target);
        return TaskStatus.SUCCESS;
    }

    @Override
    public String describe() {
        return "interacting with the " + EntityTargets.label(target);
    }
}
