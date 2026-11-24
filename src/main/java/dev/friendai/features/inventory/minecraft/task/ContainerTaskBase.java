package dev.friendai.features.inventory.minecraft.task;

import dev.friendai.debug.DebugTarget;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.interact.ContainerAccess;
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
    /** How many containers one task may open before it reports that they are all full. */
    private static final int MAX_CONTAINERS = 3;

    private final BlockPos requestedTarget;
    private final int maxContainers;
    private ContainerSession session;
    private int opened;
    private boolean nextCandidate;
    private boolean preflightDone;

    protected BlockPos containerPos;
    protected Container container;
    protected String summary = "";

    protected ContainerTaskBase(BlockPos target) {
        this(target, MAX_CONTAINERS);
    }

    protected ContainerTaskBase(BlockPos target, int maxContainers) {
        this.requestedTarget = target;
        this.maxContainers = Math.max(1, maxContainers);
    }

    private ContainerSession session() {
        if (session == null) {
            session = new ContainerSession(
                    requestedTarget,
                    this::findTarget,
                    onlyInsidePlacesInFreeTime(),
                    this::noTargetSummary,
                    position -> {
                        containerPos = position;
                        return missingTargetSummary();
                    });
        }
        return session;
    }

    /**
     * Asks the base to open the next ranked container instead of ending the task when the current one could not take
     * anything. Ignored when the task was given one exact position or has already opened its bounded number.
     */
    protected void tryAnotherContainer() {
        nextCandidate = true;
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
        ContainerSession current = session();
        TaskStatus status = current.tick(c, (context, position, openedContainer) -> {
            containerPos = position;
            container = openedContainer;
            return work(context);
        });
        if (nextCandidate) {
            nextCandidate = false;
            if (status == TaskStatus.FAILED && anotherCandidate(current, c)) {
                return TaskStatus.RUNNING;
            }
        }
        if (status == TaskStatus.FAILED
                && containerPos != null
                && ContainerAccess.at(c.level(), containerPos).isEmpty()) {
            c.storage().forget(c.companion(), c.level(), containerPos);
        }
        if (status == TaskStatus.FAILED && summary.isEmpty()) summary = current.failureSummary();
        return status;
    }

    /** Drops a container that took nothing so the next tick can pick the next ranked one. */
    private boolean anotherCandidate(ContainerSession current, TaskContext c) {
        if (requestedTarget != null || opened + 1 >= maxContainers) {
            return false;
        }
        opened++;
        current.stop(c);
        session = null;
        containerPos = null;
        container = null;
        summary = "";
        return true;
    }

    @Override
    public void stop(TaskContext c) {
        if (session != null) {
            session.stop(c);
        }
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
