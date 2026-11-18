package dev.friendai.features.brewing.minecraft.task;

import dev.friendai.features.brewing.BrewPlanner;
import dev.friendai.features.brewing.BrewRecipe;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskState;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;

/** Checkpoint decoding for {@link BrewTask}; contributed beside its definition in {@code BrewTools}. */
public final class BrewCheckpoints {
    private BrewCheckpoints() {}

    /** Retains fresh legacy tasks; a legacy batch has no coordinates to prove which stand it owns. */
    public static Optional<Task> brew(TaskState state, TaskContext context) {
        try {
            String[] parts = state.data().split("\\|", -1);
            if (parts.length != 3 && parts.length != 8) {
                return Optional.empty();
            }
            String target = parts[0];
            int bottles = Integer.parseInt(parts[1]);
            int step = Integer.parseInt(parts[2]);
            var plan = BrewPlanner.plan(target);
            if (plan.isEmpty()
                    || bottles < 1
                    || bottles > BrewRecipe.BOTTLES_PER_OPERATION
                    || step < 0
                    || step >= plan.get().steps().size()) {
                return Optional.empty();
            }
            if (parts.length == 3) {
                return step == 0 ? Optional.of(new BrewTask(target, bottles, null)) : Optional.empty();
            }
            BlockPos stand = parts[3].isEmpty() ? null : BlockPos.of(Long.parseLong(parts[3]));
            Identifier dimension = parts[4].isEmpty() ? null : Identifier.tryParse(parts[4]);
            boolean staged = Boolean.parseBoolean(parts[5]);
            String fuel = parts[6];
            int fuelBefore = Integer.parseInt(parts[7]);
            if ((!parts[5].equals("true") && !parts[5].equals("false"))
                    || (!parts[4].isEmpty()
                            && (dimension == null
                                    || stand == null
                                    || !context.level().dimension().identifier().equals(dimension)))
                    || (dimension == null && (step > 0 || staged))
                    || fuelBefore < 0
                    || (staged && fuelBefore == 0 && fuel.isEmpty())
                    || (!fuel.isEmpty() && (!staged || Identifier.tryParse(fuel) == null))) {
                return Optional.empty();
            }
            return Optional.of(new BrewTask(target, bottles, stand, dimension, step, staged, fuel, fuelBefore));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }
}
