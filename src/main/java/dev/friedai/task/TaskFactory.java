package dev.friedai.task;

import dev.friedai.task.builtin.FollowTask;
import dev.friedai.task.builtin.GoToTask;
import dev.friedai.task.builtin.StayTask;
import java.util.Optional;
import net.minecraft.world.phys.Vec3;

public final class TaskFactory {
    private TaskFactory() {}

    /** Rebuilds a task from {@link Task#saveData()}. */
    public static Optional<Task> restore(String saved) {
        if (saved == null) {
            return Optional.empty();
        }
        if (saved.equals("follow")) {
            return Optional.of(new FollowTask(true));
        }
        if (saved.equals("stay")) {
            return Optional.of(new StayTask());
        }
        if (saved.startsWith("goto:")) {
            try {
                String[] p = saved.substring(5).split(",");
                return Optional.of(new GoToTask(new Vec3(Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2]))));
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }
}
