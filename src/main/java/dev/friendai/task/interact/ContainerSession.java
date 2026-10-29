package dev.friendai.task.interact;

import dev.friendai.debug.DebugAction;
import dev.friendai.debug.DebugTarget;
import dev.friendai.features.navigation.minecraft.nav.Approach;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.autonomy.PlaceGuard;
import dev.friendai.task.autonomy.ProtectionText;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;

/** Shared walk, protection, open and close lifecycle for one storage-container action. */
public final class ContainerSession {
    @FunctionalInterface
    public interface TargetFinder {
        Optional<BlockPos> find(TaskContext context);
    }

    @FunctionalInterface
    public interface Work {
        TaskStatus run(TaskContext context, BlockPos position, Container container);
    }

    private final BlockPos requestedTarget;
    private final TargetFinder targetFinder;
    private final boolean onlyInsidePlacesInFreeTime;
    private final Supplier<String> noTargetSummary;
    private final Function<BlockPos, String> missingTargetSummary;

    private BlockPos position;
    private Approach approach;
    private Container container;
    private boolean arrived;
    private boolean opened;
    private String failureSummary = "";

    public ContainerSession(
            BlockPos requestedTarget,
            TargetFinder targetFinder,
            boolean onlyInsidePlacesInFreeTime,
            Supplier<String> noTargetSummary,
            Function<BlockPos, String> missingTargetSummary) {
        this.requestedTarget = requestedTarget;
        this.targetFinder = targetFinder;
        this.onlyInsidePlacesInFreeTime = onlyInsidePlacesInFreeTime;
        this.noTargetSummary = noTargetSummary;
        this.missingTargetSummary = missingTargetSummary;
    }

    public TaskStatus tick(TaskContext context, Work work) {
        if (!failureSummary.isEmpty()) return TaskStatus.FAILED;
        if (approach == null) {
            if (requestedTarget != null) {
                position = requestedTarget;
                if (ContainerAccess.at(context.level(), position).isEmpty()) {
                    failureSummary = missingTargetSummary.apply(position);
                    return TaskStatus.FAILED;
                }
            } else {
                Optional<BlockPos> found = targetFinder == null ? Optional.empty() : targetFinder.find(context);
                if (found.isEmpty()) {
                    failureSummary = noTargetSummary.get();
                    return TaskStatus.FAILED;
                }
                position = found.get();
            }
            if (onlyInsidePlacesInFreeTime
                    && context.autonomous()
                    && PlaceGuard.enabled(context)
                    && !PlaceGuard.insideAnyPlace(context, position)) {
                failureSummary = "container is outside known places";
                return TaskStatus.FAILED;
            }
            approach = new Approach(position);
        }

        if (!arrived) {
            switch (approach.tick(context)) {
                case UNREACHABLE -> {
                    failureSummary = "can't reach " + coords(position);
                    return TaskStatus.FAILED;
                }
                case MOVING -> {
                    return TaskStatus.RUNNING;
                }
                case IN_REACH -> arrived = true;
            }
        }

        Optional<Container> at = ContainerAccess.at(context.level(), position);
        if (at.isEmpty()) {
            failureSummary = missingTargetSummary.apply(position);
            return TaskStatus.FAILED;
        }
        container = at.get();
        if (!context.companion().hands().canInteract(position)) {
            failureSummary = coords(position) + " is " + ProtectionText.describe(context, position);
            return TaskStatus.FAILED;
        }
        if (!opened) {
            container.startOpen(context.companion().hands().player());
            opened = true;
        }
        boolean keepOpen = false;
        try {
            TaskStatus status = work.run(context, position, container);
            if (context.level() instanceof ServerLevel level) {
                ContainerAccess.recordContents(level, context.companion(), position, container);
            }
            keepOpen = status == TaskStatus.RUNNING;
            return status;
        } finally {
            if (!keepOpen) close(context);
        }
    }

    public void stop(TaskContext context) {
        if (approach != null) approach.stop(context);
        close(context);
    }

    public BlockPos destination() {
        return position != null ? position : requestedTarget;
    }

    public BlockPos position() {
        return position;
    }

    public Container container() {
        return container;
    }

    public String failureSummary() {
        return failureSummary;
    }

    public List<DebugTarget> debugTargets() {
        BlockPos destination = destination();
        return destination == null
                ? List.of()
                : List.of(new DebugTarget(
                        destination.getX(), destination.getY(), destination.getZ(), DebugAction.USE, "container"));
    }

    private void close(TaskContext context) {
        if (opened && container != null) {
            container.stopOpen(context.companion().hands().player());
            opened = false;
        }
    }

    private static String coords(BlockPos position) {
        return position.getX() + " " + position.getY() + " " + position.getZ();
    }
}
