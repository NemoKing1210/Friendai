package dev.friendai.features.navigation.minecraft.task;

import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskState;
import java.util.Optional;
import net.minecraft.world.phys.Vec3;

/** Checkpoint decoding for the navigation tasks; contributed beside their definitions. */
public final class NavigationCheckpoints {
    private NavigationCheckpoints() {}

    public static Optional<Task> follow(TaskState state, TaskContext context) {
        return Optional.of(new FollowTask(true));
    }

    public static Optional<Task> stay(TaskState state, TaskContext context) {
        return Optional.of(new StayTask());
    }

    public static Optional<Task> goTo(TaskState state, TaskContext context) {
        try {
            String[] p = state.data().split(",");
            if (p.length != 3) return Optional.empty();
            return Optional.of(new GoToTask(
                    new Vec3(Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2]))));
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }
}
