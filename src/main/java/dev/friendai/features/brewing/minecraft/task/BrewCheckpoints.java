package dev.friendai.features.brewing.minecraft.task;

import dev.friendai.features.brewing.BrewPlanner;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskState;
import java.util.Optional;

/** Checkpoint decoding for {@link BrewTask}; contributed beside its definition in {@code BrewTools}. */
public final class BrewCheckpoints {
    private BrewCheckpoints() {}

    /**
     * Restores the next unfinished step. The stand itself is world state and is kept by the block entity; the task
     * re-finds the nearest stand, while the step index prevents a completed operation from running again.
     */
    public static Optional<Task> brew(TaskState state, TaskContext context) {
        try {
            String[] parts = state.data().split("\\|");
            if (parts.length != 3) {
                return Optional.empty();
            }
            String target = parts[0];
            int bottles = Integer.parseInt(parts[1]);
            int step = Integer.parseInt(parts[2]);
            if (BrewPlanner.plan(target).isEmpty() || bottles < 1 || bottles > 3 || step < 0) {
                return Optional.empty();
            }
            return Optional.of(new BrewTask(target, bottles, null, step));
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }
}
