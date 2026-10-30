package dev.friendai.features.farming.minecraft.task;

import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskState;
import java.util.Optional;
import net.minecraft.core.BlockPos;

/** Checkpoint decoding for the farming tasks; contributed beside their definitions. */
public final class FarmCheckpoints {
    private FarmCheckpoints() {}

    public static Optional<Task> tendFarm(TaskState state, TaskContext context) {
        try {
            String[] p = state.data().split(",");
            if (p.length != 4) return Optional.empty();
            return Optional.of(new TendFarmTask(
                    new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])),
                    Integer.parseInt(p[3])));
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }
}
