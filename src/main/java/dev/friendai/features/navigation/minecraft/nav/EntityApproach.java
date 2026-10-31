package dev.friendai.features.navigation.minecraft.nav;

import dev.friendai.task.TaskContext;
import net.minecraft.world.entity.Entity;

/** Shared movement operation for reaching a moving entity. Tasks keep their own arrival action and reporting. */
public final class EntityApproach {
    public enum State {
        MOVING,
        REACHED,
        UNREACHABLE,
        TARGET_GONE
    }

    private final double reach;
    private final MoveHelper mover;

    public EntityApproach(double reach) {
        this.reach = reach;
        this.mover = new MoveHelper(1.0, Math.max(1.0, reach * 0.8), 1.5, 8);
    }

    public void reset(TaskContext context) {
        mover.reset(context);
    }

    public State tick(TaskContext context, Entity target) {
        if (!target.isAlive() || target.isRemoved() || target.level() != context.level()) {
            return State.TARGET_GONE;
        }
        if (context.companion().distanceTo(target) <= reach) {
            mover.stop(context);
            return State.REACHED;
        }
        return switch (mover.tick(context, target.position())) {
            case STUCK -> State.UNREACHABLE;
            // The route's goal band is wider than the reach, so it can end with the entity just outside it, and the
            // navigator keeps reporting that arrival. Walk the last step by hand instead of reporting movement that
            // never happens, which would leave the task running until its timeout.
            case ARRIVED -> {
                MoveHelper.State lastStep = mover.align(context, target.getX(), target.getZ());
                if (lastStep == MoveHelper.State.MOVING) {
                    yield State.MOVING;
                }
                mover.stop(context);
                yield lastStep == MoveHelper.State.ARRIVED
                                && context.companion().distanceTo(target) <= reach
                        ? State.REACHED
                        : State.UNREACHABLE;
            }
            case MOVING -> State.MOVING;
        };
    }

    public void stop(TaskContext context) {
        mover.stop(context);
    }
}
