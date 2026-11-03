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
        return Optional.of(new FollowTask("quiet".equals(state.data())));
    }

    public static Optional<Task> stay(TaskState state, TaskContext context) {
        int remaining = ticksLeft(state.data());
        // Zero means the checkpoint carried no usable budget: the configured timeout, not a one-tick wait.
        return Optional.of(remaining > 0 ? new StayTask(remaining) : new StayTask());
    }

    private static int ticksLeft(String data) {
        try {
            return data == null ? 0 : Math.max(0, Integer.parseInt(data.strip()));
        } catch (NumberFormatException invalid) {
            return 0;
        }
    }

    public static Optional<Task> goTo(TaskState state, TaskContext context) {
        return GoToTask.Checkpoint.decode(state.data())
                .map(walk -> new GoToTask(new Vec3(walk.x(), walk.y(), walk.z()), walk.sneaking()));
    }

    public static Optional<Task> jump(TaskState state, TaskContext context) {
        return Optional.of(new JumpTask());
    }

    public static Optional<Task> travelThroughPortal(TaskState state, TaskContext context) {
        // An empty or garbled destination must not restore: the task would only fail its first tick.
        return TravelThroughPortalTask.isSupportedDestination(state.data())
                ? Optional.of(new TravelThroughPortalTask(state.data()))
                : Optional.empty();
    }
}
