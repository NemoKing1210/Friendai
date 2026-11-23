package dev.friendai.features.inventory.minecraft.task;

import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskState;
import dev.friendai.task.inventory.ItemMatcher;
import java.util.Optional;
import net.minecraft.core.BlockPos;

/**
 * Checkpoint decoding for {@link MoveItemsTask}, contributed beside its definition in {@code StorageTools}. Only a
 * transfer whose withdrawal already committed is restorable: repeating that request would take the items twice.
 */
public final class MoveItemsCheckpoints {
    private MoveItemsCheckpoints() {}

    public static Optional<Task> move(TaskState state, TaskContext context) {
        try {
            String[] parts = state.data().split("\\|", -1);
            if (parts.length != 9 || !"v1".equals(parts[0])) {
                return Optional.empty();
            }
            String label = parts[1];
            int count = Integer.parseInt(parts[2]);
            BlockPos from = parts[3].isEmpty() ? null : BlockPos.of(Long.parseLong(parts[3]));
            BlockPos to = parts[4].isEmpty() ? null : BlockPos.of(Long.parseLong(parts[4]));
            boolean storing = "STORE".equals(parts[5]);
            boolean returning = "RETURN".equals(parts[5]);
            int moved = Integer.parseInt(parts[6]);
            int inserted = Integer.parseInt(parts[7]);
            int leftover = Integer.parseInt(parts[8]);
            if (from == null
                    || to == null
                    || from.equals(to)
                    || (!storing && !returning)
                    || count < 1
                    || moved < 1
                    || moved > count
                    || inserted < 0
                    || inserted > moved
                    || leftover < 0
                    || leftover != moved - inserted) {
                return Optional.empty();
            }
            Optional<ItemMatcher> matcher = ItemMatcher.parse(label);
            if (matcher.isEmpty()) return Optional.empty();
            MoveItemsTask.Phase phase = storing ? MoveItemsTask.Phase.STORE : MoveItemsTask.Phase.RETURN;
            return Optional.of(MoveItemsTask.resumed(matcher.get(), count, from, to, phase, moved, inserted, leftover));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }
}
