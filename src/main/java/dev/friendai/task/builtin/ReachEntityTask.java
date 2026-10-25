package dev.friendai.task.builtin;

import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.interact.EntityTargets;
import dev.friendai.task.nav.MoveHelper;
import net.minecraft.world.entity.Entity;

/**
 * Walks up to an entity that may itself be moving and hands over once the companion is within {@code reach}. The
 * subclass says what to do there.
 */
abstract class ReachEntityTask implements Task {
    protected final Entity target;
    private final double reach;
    private final MoveHelper mover;
    protected String summary = "";

    ReachEntityTask(Entity target, double reach) {
        this.target = target;
        this.reach = reach;
        this.mover = new MoveHelper(1.0, Math.max(1.0, reach * 0.8), 1.5, 8);
    }

    /** Called every tick while the companion is within reach; returns the task's status. */
    abstract TaskStatus reached(TaskContext c);

    @Override
    public void start(TaskContext c) {
        mover.reset(c);
    }

    @Override
    public final TaskStatus tick(TaskContext c) {
        if (!target.isAlive() || target.isRemoved() || target.level() != c.level()) {
            summary = "the " + EntityTargets.label(target) + " is gone";
            return TaskStatus.FAILED;
        }
        if (c.companion().distanceTo(target) <= reach) {
            mover.stop(c);
            return reached(c);
        }
        if (mover.tick(c.companion(), target.position()) == MoveHelper.State.STUCK) {
            summary = "can't reach the " + EntityTargets.label(target);
            return TaskStatus.FAILED;
        }
        return TaskStatus.RUNNING;
    }

    @Override
    public void stop(TaskContext c) {
        mover.stop(c);
    }

    @Override
    public String resultSummary() {
        return summary;
    }
}
