package dev.friedai.task.builtin;

import dev.friedai.task.Task;
import dev.friedai.task.TaskContext;
import dev.friedai.task.TaskStatus;
import dev.friedai.task.inventory.InventoryOps;
import dev.friedai.task.inventory.ItemMatcher;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

/** Drops matching items from the inventory at the companion's feet. Finishes on its first tick. */
public final class DropItemsTask implements Task {
    private static final int PICKUP_DELAY_TICKS = 40;

    private final ItemMatcher matcher;
    private final int count;
    private String summary = "";

    public DropItemsTask(ItemMatcher matcher, int count) {
        this.matcher = matcher;
        this.count = count;
    }

    @Override
    public String type() {
        return "drop_items";
    }

    @Override
    public void start(TaskContext c) {}

    @Override
    public TaskStatus tick(TaskContext c) {
        var companion = c.companion();
        int have = InventoryOps.count(companion.inventory(), matcher);
        if (have == 0) {
            summary = "I don't have " + matcher.label();
            return TaskStatus.FAILED;
        }
        int dropped = 0;
        for (ItemStack stack : InventoryOps.remove(companion.inventory(), matcher, Math.min(count, have))) {
            ItemEntity entity = new ItemEntity(c.level(), companion.getX(), companion.getY(), companion.getZ(), stack);
            entity.setThrower(companion);
            entity.setPickUpDelay(PICKUP_DELAY_TICKS);
            c.level().addFreshEntity(entity);
            dropped += stack.getCount();
        }
        summary = "dropped " + dropped + " " + matcher.label();
        return TaskStatus.SUCCESS;
    }

    @Override
    public void stop(TaskContext c) {}

    @Override
    public String describe() {
        return "dropping " + count + " " + matcher.label();
    }

    @Override
    public String resultSummary() {
        return summary;
    }
}
