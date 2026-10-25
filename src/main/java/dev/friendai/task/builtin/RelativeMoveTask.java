package dev.friendai.task.builtin;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.nav.MoveHelper;
import java.util.function.Function;
import net.minecraft.world.phys.Vec3;

/**
 * Walks to a point worked out when the task starts, from where the companion stands and which way it faces: a few
 * steps in a direction, or away from something.
 */
public final class RelativeMoveTask implements Task {
    private final String type;
    private final String description;
    private final Function<CompanionEntity, Vec3> planner;
    private final SneakScope sneak;
    private final MoveHelper mover = new MoveHelper(1.0, 0.8);
    private Vec3 target;
    private String summary = "";

    /**
     * @param type    the tool name the task answers to
     * @param planner picks the destination from the companion at the moment the task starts
     * @param sneak   whether to crouch for the walk
     */
    public RelativeMoveTask(String type, String description, Function<CompanionEntity, Vec3> planner, boolean sneak) {
        this.type = type;
        this.description = description;
        this.planner = planner;
        this.sneak = new SneakScope(sneak);
    }

    @Override
    public String type() {
        return type;
    }

    @Override
    public void start(TaskContext c) {
        target = planner.apply(c.companion());
        mover.reset(c);
        sneak.enter(c);
    }

    @Override
    public TaskStatus tick(TaskContext c) {
        switch (mover.tick(c.companion(), target)) {
            case ARRIVED -> {
                summary = "moved to " + (int) Math.floor(target.x) + " " + (int) Math.floor(target.y) + " "
                        + (int) Math.floor(target.z);
                return TaskStatus.SUCCESS;
            }
            case STUCK -> {
                summary = "something is in the way";
                return TaskStatus.FAILED;
            }
            default -> {
                return TaskStatus.RUNNING;
            }
        }
    }

    @Override
    public void stop(TaskContext c) {
        mover.stop(c);
        sneak.exit(c);
    }

    @Override
    public String describe() {
        return description;
    }

    @Override
    public String resultSummary() {
        return summary;
    }
}
