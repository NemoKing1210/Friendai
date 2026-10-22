package dev.friedai.task.builtin;

import dev.friedai.task.Task;
import dev.friedai.task.TaskContext;
import dev.friedai.task.TaskStatus;
import dev.friedai.task.nav.MoveHelper;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Walks to dropped items within a radius of where it started and collects them. */
public final class PickupItemsTask implements Task {
    private static final double COLLECT_RADIUS = 1.5;

    private final int radius;
    private final MoveHelper mover = new MoveHelper(1.0, 1.0);
    private final Set<UUID> unreachable = new HashSet<>();
    private Vec3 origin;
    private UUID currentTarget;
    private int collected;
    private int arrivedTicks;
    private String summary = "";

    public PickupItemsTask(int radius) {
        this.radius = radius;
    }

    @Override
    public String type() {
        return "pickup_items";
    }

    @Override
    public void start(TaskContext c) {
        origin = c.companion().position();
    }

    @Override
    public TaskStatus tick(TaskContext c) {
        var companion = c.companion();
        collected += companion.collectNearbyItems(COLLECT_RADIUS);

        List<ItemEntity> nearby = c.level().getEntitiesOfClass(
                ItemEntity.class, new AABB(origin, origin).inflate(radius), e -> !e.isRemoved() && !unreachable.contains(e.getUUID()));
        List<ItemEntity> collectible = nearby.stream().filter(e -> companion.inventory().canAddItem(e.getItem())).toList();
        if (collectible.isEmpty()) {
            boolean full = !nearby.isEmpty();
            summary = (full ? "inventory full, picked up " : "picked up ") + collected + " items";
            return TaskStatus.SUCCESS;
        }
        ItemEntity target = collectible.stream()
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(companion)))
                .orElseThrow();
        if (!target.getUUID().equals(currentTarget)) {
            currentTarget = target.getUUID();
            mover.reset();
        }
        MoveHelper.State state = mover.tick(companion, target.position());
        // Arriving collects the item on the next tick; an item that survives 20 ticks of that is given up on.
        arrivedTicks = state == MoveHelper.State.ARRIVED ? arrivedTicks + 1 : 0;
        if (state == MoveHelper.State.STUCK || arrivedTicks > 20) {
            unreachable.add(target.getUUID());
            currentTarget = null;
            arrivedTicks = 0;
        }
        return TaskStatus.RUNNING;
    }

    @Override
    public void stop(TaskContext c) {
        c.companion().getNavigation().stop();
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
