package dev.friendai.task.builtin;

import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.interact.ContainerAccess;
import dev.friendai.core.FriendaiServerState;
import dev.friendai.task.nav.Approach;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;

/**
 * Walks to a storage container, checks protection, opens the lid, and lets the subclass do the work.
 * The lid is closed on every terminal status and on {@link #stop}.
 */
abstract class ContainerTaskBase implements Task {
    protected static final int RADIUS = 16;

    private final BlockPos target;
    private Approach approach;
    private boolean arrived;
    private boolean opened;
    private boolean preflightDone;

    protected BlockPos containerPos;
    protected Container container;
    protected String summary = "";

    protected ContainerTaskBase(BlockPos target) {
        this.target = target;
    }

    /** Called on the first tick before anything else; a summary means the task fails with it. */
    protected Optional<String> preflight(TaskContext c) {
        return Optional.empty();
    }

    /** Finds the container when no coordinates were given. */
    protected abstract Optional<BlockPos> findTarget(ServerLevel level, dev.friendai.entity.CompanionEntity companion);

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
        if (approach == null) {
            if (target != null) {
                containerPos = target;
                if (ContainerAccess.at(c.level(), target).isEmpty()) {
                    summary = missingTargetSummary();
                    return TaskStatus.FAILED;
                }
            } else {
                Optional<BlockPos> found = findTarget(c.level(), c.companion());
                if (found.isEmpty()) {
                    summary = noTargetSummary();
                    return TaskStatus.FAILED;
                }
                containerPos = found.get();
            }
            if (onlyInsidePlacesInFreeTime() && c.autonomous()
                    && dev.friendai.task.autonomy.PlaceGuard.enabled(c.companion())
                    && !dev.friendai.task.autonomy.PlaceGuard.insideAnyPlace(c.companion(), containerPos)) {
                summary = "container is outside known places";
                return TaskStatus.FAILED;
            }
            approach = new Approach(containerPos);
        }

        if (!arrived) {
            switch (approach.tick(c.companion())) {
                case UNREACHABLE -> {
                    summary = "can't reach " + coords();
                    return TaskStatus.FAILED;
                }
                case MOVING -> {
                    return TaskStatus.RUNNING;
                }
                case IN_REACH -> arrived = true;
            }
        }

        Optional<Container> at = ContainerAccess.at(c.level(), containerPos);
        if (at.isEmpty()) {
            summary = missingTargetSummary();
            return TaskStatus.FAILED;
        }
        container = at.get();
        if (!c.companion().hands().canInteract(containerPos)) {
            summary = coords() + " is protected";
            return TaskStatus.FAILED;
        }
        if (!opened) {
            container.startOpen(c.companion().hands().player());
            opened = true;
        }
        TaskStatus status = work(c);
        if (opened && container != null && c.level() instanceof ServerLevel level) {
            ContainerAccess.recordContents(level, c.companion(), containerPos, container);
        }
        if (status != TaskStatus.RUNNING) {
            close(c);
        }
        return status;
    }

    @Override
    public void stop(TaskContext c) {
        if (approach != null) {
            approach.stop(c.companion());
        }
        close(c);
    }

    protected void close(TaskContext c) {
        if (opened && container != null) {
            container.stopOpen(c.companion().hands().player());
            opened = false;
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
