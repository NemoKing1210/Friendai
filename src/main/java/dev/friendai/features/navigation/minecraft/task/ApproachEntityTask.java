package dev.friendai.features.navigation.minecraft.task;

import dev.friendai.features.navigation.minecraft.nav.EntityApproach;
import dev.friendai.features.navigation.minecraft.nav.EntityTargets;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import net.minecraft.world.entity.Entity;

/** Navigation-owned approach action used by the movement tool without constructing an interaction task. */
public final class ApproachEntityTask implements Task {
    private static final int LOOK_TICKS = 40;

    private final Entity target;
    private final double distance;
    private final EntityApproach approach;
    private String summary = "";

    public ApproachEntityTask(Entity target, double distance) {
        this.target = target;
        this.distance = distance;
        this.approach = new EntityApproach(distance);
    }

    @Override
    public String type() {
        return "approach";
    }

    @Override
    public void start(TaskContext context) {
        approach.reset(context);
    }

    @Override
    public TaskStatus tick(TaskContext context) {
        return switch (approach.tick(context, target)) {
            case TARGET_GONE -> {
                summary = "the " + EntityTargets.label(target) + " is gone";
                yield TaskStatus.FAILED;
            }
            case UNREACHABLE -> {
                summary = "can't reach the " + EntityTargets.label(target);
                yield TaskStatus.FAILED;
            }
            case REACHED -> {
                context.companion().body().holdLook(target::getEyePosition, LOOK_TICKS);
                summary = "standing " + Math.round(context.companion().distanceTo(target)) + " blocks from the "
                        + EntityTargets.label(target);
                yield TaskStatus.SUCCESS;
            }
            case MOVING -> TaskStatus.RUNNING;
        };
    }

    @Override
    public void stop(TaskContext context) {
        approach.stop(context);
    }

    @Override
    public String describe() {
        return "walking up to the " + EntityTargets.label(target) + " (to within " + distance + " blocks)";
    }

    @Override
    public String resultSummary() {
        return summary;
    }
}
