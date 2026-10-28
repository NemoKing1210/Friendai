package dev.friendai.task.builtin;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.survival.HungerModel;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.combat.CombatController;
import dev.friendai.task.combat.Food;
import java.util.Locale;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/** Eats one food item from the inventory, or the best safe food, until it is consumed. */
public final class EatTask implements Task {
    private static final int MAX_ATTEMPTS = 3;

    private final Item item;
    private int slot = -1;
    private Item eaten;
    private int initialTotal;
    private boolean started;
    private int attempts;
    private String summary = "";

    /** @param item the food to eat, or null to pick the best safe food */
    public EatTask(@Nullable Item item) {
        this.item = item;
    }

    @Override
    public String type() {
        return "eat";
    }

    @Override
    public void start(TaskContext c) {}

    @Override
    public TaskStatus tick(TaskContext c) {
        CompanionEntity companion = c.companion();
        CombatController controller = companion.combatController();
        if (!started) {
            TaskStatus chosen = choose(companion);
            if (chosen != null) {
                return chosen;
            }
            if (!controller.eatFromSlot(slot)) {
                return TaskStatus.RUNNING;
            }
            started = true;
            return TaskStatus.RUNNING;
        }
        if (controller.isEating()) {
            return TaskStatus.RUNNING;
        }
        if (companion.inventory().countItem(eaten) < initialTotal) {
            summary = "ate " + id(eaten)
                    + ", hunger " + number(companion.hunger().food()) + "/" + HungerModel.MAX_FOOD
                    + ", health " + number(companion.getHealth()) + "/" + number(companion.getMaxHealth());
            return TaskStatus.SUCCESS;
        }
        if (++attempts >= MAX_ATTEMPTS) {
            summary = "I couldn't eat " + id(eaten);
            return TaskStatus.FAILED;
        }
        started = false;
        return TaskStatus.RUNNING;
    }

    /** Finds and validates the food; a non-null status means the task is already decided. */
    private @Nullable TaskStatus choose(CompanionEntity companion) {
        if (item != null) {
            String id = id(item);
            slot = -1;
            for (int i = 0; i < companion.inventory().getContainerSize(); i++) {
                if (companion.inventory().getItem(i).is(item)) {
                    slot = i;
                    break;
                }
            }
            if (slot < 0) {
                summary = "I don't have " + id;
                return TaskStatus.FAILED;
            }
            ItemStack stack = companion.inventory().getItem(slot);
            if (stack.get(DataComponents.FOOD) == null) {
                summary = id + " is not food";
                return TaskStatus.FAILED;
            }
            if (!Food.isSafe(companion, stack, true)) {
                summary = "I won't eat " + id;
                return TaskStatus.FAILED;
            }
            eaten = stack.getItem();
        } else {
            var found = Food.choose(companion, false);
            if (found.isEmpty()) {
                summary = "I have no food I can eat";
                return TaskStatus.FAILED;
            }
            slot = found.getAsInt();
            eaten = companion.inventory().getItem(slot).getItem();
        }
        initialTotal = companion.inventory().countItem(eaten);
        return null;
    }

    private static String id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    private static String number(float value) {
        return value == Math.rint(value) ? String.valueOf((int) value) : String.format(Locale.ROOT, "%.1f", value);
    }

    @Override
    public void stop(TaskContext c) {
        c.companion().combatController().stopEating();
        c.companion().getNavigation().stop();
    }

    @Override
    public String describe() {
        return item == null ? "eating food" : "eating " + id(item);
    }

    @Override
    public String resultSummary() {
        return summary;
    }
}
