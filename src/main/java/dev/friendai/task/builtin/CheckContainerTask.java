package dev.friendai.task.builtin;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.interact.ContainerAccess;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/** Walks to a container, opens it for a moment and reports what is inside. */
public final class CheckContainerTask extends ContainerTaskBase {
    public static final int OPEN_TICKS = 10;
    public static final int MAX_KINDS = 20;

    private int openTicks;

    public CheckContainerTask(BlockPos target) {
        super(target);
    }

    @Override
    public String type() {
        return "check_container";
    }

    @Override
    public void start(TaskContext c) {}

    @Override
    protected Optional<BlockPos> findTarget(ServerLevel level, CompanionEntity companion) {
        return ContainerAccess.nearest(companion, RADIUS);
    }

    @Override
    protected String noTargetSummary() {
        return "no storage container within " + RADIUS + " blocks";
    }

    @Override
    protected String missingTargetSummary() {
        return "there is no chest, barrel or other storage at " + coords();
    }

    @Override
    protected TaskStatus work(TaskContext c) {
        if (openTicks < OPEN_TICKS) {
            openTicks++;
            return TaskStatus.RUNNING;
        }
        String containerId = ContainerAccess.label(c.level(), containerPos);
        Map<String, Integer> counts = new LinkedHashMap<>();
        int used = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            used++;
            String name = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
            counts.merge(name, stack.getCount(), Integer::sum);
        }
        if (counts.isEmpty()) {
            summary = containerId + " at " + coords() + " is empty";
            return TaskStatus.SUCCESS;
        }
        StringBuilder text = new StringBuilder(containerId).append(" at ").append(coords()).append(" holds: ");
        int shown = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (shown == MAX_KINDS) {
                text.append(", … and ").append(counts.size() - MAX_KINDS).append(" more kinds");
                break;
            }
            if (shown > 0) {
                text.append(", ");
            }
            text.append(entry.getKey()).append('×').append(entry.getValue());
            shown++;
        }
        text.append(" (").append(used).append(" of ").append(container.getContainerSize()).append(" slots used)");
        summary = text.toString();
        return TaskStatus.SUCCESS;
    }

    @Override
    public String describe() {
        return "checking a container";
    }
}
