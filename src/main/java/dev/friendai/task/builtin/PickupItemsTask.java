package dev.friendai.task.builtin;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.inventory.DropCollector;
import dev.friendai.task.inventory.ItemMatcher;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Walks to dropped items within a radius of where it started and collects them. */
public final class PickupItemsTask implements Task {
    private final int radius;
    private final @Nullable ItemMatcher filter;
    private DropCollector collector;
    private Vec3 origin;
    private String summary = "";

    public PickupItemsTask(int radius) {
        this(radius, null);
    }

    /** @param filter when given, only item entities matching it are picked up; the rest stay on the ground */
    public PickupItemsTask(int radius, @Nullable ItemMatcher filter) {
        this.radius = radius;
        this.filter = filter;
    }

    @Override
    public String type() {
        return "pickup_items";
    }

    @Override
    public void start(TaskContext c) {
        origin = c.companion().position();
        collector = new DropCollector(origin, radius, filter);
    }

    @Override
    public TaskStatus tick(TaskContext c) {
        if (collector.tick(c) == DropCollector.State.COLLECTING) {
            return TaskStatus.RUNNING;
        }
        boolean full = remainsBecauseFull(c);
        summary = (full ? "inventory full, picked up " : "picked up ") + collector.collected() + " items";
        return TaskStatus.SUCCESS;
    }

    @Override
    public void stop(TaskContext c) {
        if (collector != null) {
            collector.stop(c);
        }
    }

    /** Whether something this task wanted is left on the ground only because there is no room for it. */
    private boolean remainsBecauseFull(TaskContext c) {
        CompanionEntity companion = c.companion();
        return c.level()
                .getEntitiesOfClass(ItemEntity.class, new AABB(origin, origin).inflate(radius),
                        e -> companion.canCollect(e) && (filter == null || filter.test(e.getItem())))
                .stream()
                .anyMatch(e -> !companion.inventory().canAddItem(e.getItem()));
    }

    @Override
    public String describe() {
        return "picking up items within " + radius + " blocks";
    }

    @Override
    public String resultSummary() {
        return summary;
    }
}
