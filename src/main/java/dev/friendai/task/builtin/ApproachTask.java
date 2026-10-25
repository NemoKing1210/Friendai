package dev.friendai.task.builtin;

import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.interact.EntityTargets;
import net.minecraft.world.entity.Entity;

/** Walks up to an entity, stops at the wanted distance and looks at it. */
public final class ApproachTask extends ReachEntityTask {
    private static final int LOOK_TICKS = 40;

    private final double distance;

    public ApproachTask(Entity target, double distance) {
        super(target, distance);
        this.distance = distance;
    }

    @Override
    public String type() {
        return "approach";
    }

    @Override
    TaskStatus reached(TaskContext c) {
        c.companion().body().holdLook(target::getEyePosition, LOOK_TICKS);
        summary = "standing " + Math.round(c.companion().distanceTo(target)) + " blocks from the "
                + EntityTargets.label(target);
        return TaskStatus.SUCCESS;
    }

    @Override
    public String describe() {
        return "walking up to the " + EntityTargets.label(target) + " (to within " + distance + " blocks)";
    }
}
