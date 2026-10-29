package dev.friendai.features.inventory.minecraft.task;

import dev.friendai.debug.DebugTarget;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.interact.ContainerSession;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;

/**
 * Task adapter for storage operations; the shared walk/open lifecycle is composed from {@link ContainerSession}.
 */
abstract class ContainerTaskBase implements Task {
    protected static final int RADIUS = 16;

    private final ContainerSession session;
    private boolean preflightDone;

    protected BlockPos containerPos;
    protected Container container;
    protected String summary = "";

    protected ContainerTaskBase(BlockPos target) {
        this.session = new ContainerSession(
                target, this::findTarget, onlyInsidePlacesInFreeTime(), this::noTargetSummary, position -> {
                    containerPos = position;
                    return missingTargetSummary();
                });
    }

    @Override
    public List<DebugTarget> debugTargets() {
        return session.debugTargets();
    }

    /** Called on the first tick before anything else; a summary means the task fails with it. */
    protected Optional<String> preflight(TaskContext c) {
        return Optional.empty();
    }

    /** Finds the container when no coordinates were given. */
    protected abstract Optional<BlockPos> findTarget(TaskContext context);

    /** Free time may use this task only on containers inside the owner's known places. */
    protected boolean onlyInsidePlacesInFreeTime() {
        return false;
    }

    protected abstract String noTargetSummary();

    protected abstract String missingTargetSummary();

    /** Runs once the container is open. Returning anything but RUNNING closes the lid. */
    protected abstract TaskStatus work(TaskContext c);

    @Override
    public TaskStatus tick(TaskContext c) {
        if (!preflightDone) {
            Optional<String> failure = preflight(c);
            if (failure.isPresent()) {
                summary = failure.get();
                return TaskStatus.FAILED;
            }
            preflightDone = true;
        }
        TaskStatus status = session.tick(c, (context, position, openedContainer) -> {
            containerPos = position;
            container = openedContainer;
            return work(context);
        });
        if (status == TaskStatus.FAILED && summary.isEmpty()) summary = session.failureSummary();
        return status;
    }

    @Override
    public void stop(TaskContext c) {
        session.stop(c);
    }

    protected String coords() {
        return containerPos == null ? "" : coords(containerPos);
    }

    protected static String coords(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    @Override
    public String resultSummary() {
        return summary;
    }
}
