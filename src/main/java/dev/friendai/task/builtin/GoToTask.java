package dev.friendai.task.builtin;

import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.nav.MoveHelper;
import java.util.Locale;
import net.minecraft.world.phys.Vec3;

public final class GoToTask implements Task {
    private final Vec3 target;
    private final MoveHelper mover = new MoveHelper(1.0, 1.5);
    private final SneakScope sneak;
    private String summary = "";

    public GoToTask(Vec3 target) {
        this(target, false);
    }

    /** @param sneak whether to crouch for the walk, which is slower but quieter */
    public GoToTask(Vec3 target, boolean sneak) {
        this.target = target;
        this.sneak = new SneakScope(sneak);
    }

    private String coords() {
        return (int) Math.floor(target.x) + " " + (int) Math.floor(target.y) + " " + (int) Math.floor(target.z);
    }

    @Override
    public String type() {
        return "go_to";
    }

    @Override
    public void start(TaskContext c) {
        mover.reset(c);
        sneak.enter(c);
    }

    @Override
    public TaskStatus tick(TaskContext c) {
        switch (mover.tick(c.companion(), target)) {
            case ARRIVED -> {
                summary = "reached " + coords();
                return TaskStatus.SUCCESS;
            }
            case STUCK -> {
                summary = "can't reach " + coords();
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
        c.companion().getNavigation().stop();
        sneak.exit(c);
    }

    @Override
    public String describe() {
        return "walking to " + coords();
    }

    @Override
    public String resultSummary() {
        return summary;
    }

    @Override
    public String saveData() {
        return String.format(Locale.ROOT, "%f,%f,%f", target.x, target.y, target.z);
    }
}
