package dev.friendai.features.interaction.minecraft.task;

import dev.friendai.features.navigation.minecraft.nav.EntityApproach;
import dev.friendai.features.navigation.minecraft.nav.EntityTargets;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import net.minecraft.world.entity.Entity;

/**
 * Walks up to an entity that may itself be moving and hands over once the companion is within {@code reach}. The
 * subclass says what to do there.
 */
abstract class ReachEntityTask implements Task {
    protected final Entity target;
    private final EntityApproach approach;
    protected String summary = "";

    ReachEntityTask(Entity target, double reach) {
        this.target = target;
        this.approach = new EntityApproach(reach);
    }

    /** Called every tick while the companion is within reach; returns the task's status. */
    abstract TaskStatus reached(TaskContext c);

    @Override
    public void start(TaskContext c) {
        approach.reset(c);
    }

    @Override
    public final TaskStatus tick(TaskContext c) {
        return switch (approach.tick(c, target)) {
            case TARGET_GONE -> {
                summary = "the " + EntityTargets.label(target) + " is gone";
                yield TaskStatus.FAILED;
            }
            case UNREACHABLE -> {
                summary = "can't reach the " + EntityTargets.label(target);
                yield TaskStatus.FAILED;
            }
            case REACHED -> reached(c);
            case MOVING -> TaskStatus.RUNNING;
        };
    }

    @Override
    public void stop(TaskContext c) {
        approach.stop(c);
    }

    @Override
    public String resultSummary() {
        return summary;
    }
}
