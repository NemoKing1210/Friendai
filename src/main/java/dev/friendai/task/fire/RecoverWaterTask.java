package dev.friendai.task.fire;

import dev.friendai.features.navigation.pathing.Pos;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.nav.Approach;
import java.util.List;
import net.minecraft.core.BlockPos;

/**
 * Takes back a water source a cancelled douse left behind, so "always recover the bucket" also holds when the
 * companion is stopped between pouring and recovering. The runner starts it in place of the default activity, the
 * way {@code ScaffoldCleanupTask} does for a build.
 */
public final class RecoverWaterTask implements Task {
    /** Far longer than a walk of a few blocks; past it the source is reported as left behind. */
    public static final int TIMEOUT_TICKS = 200;

    private final Pos source;
    private Approach approach;
    private int ticks;
    private String summary = "";

    public RecoverWaterTask(Pos source) {
        this.source = source;
    }

    @Override
    public String type() {
        return "recover_water";
    }

    @Override
    public void start(TaskContext c) {
        approach = new Approach(List.of(blockPos()), false);
    }

    @Override
    public TaskStatus tick(TaskContext c) {
        FireHands hands = new FireHands(c);
        if (!hands.holdsSource(blockPos())) {
            summary = "the water was already gone";
            return TaskStatus.SUCCESS;
        }
        if (++ticks > TIMEOUT_TICKS) {
            summary = "left the water at " + source.x() + " " + source.y() + " " + source.z();
            return TaskStatus.FAILED;
        }
        Approach.State state = approach.tick(c.companion());
        if (state == Approach.State.MOVING) {
            return TaskStatus.RUNNING;
        }
        if (state == Approach.State.UNREACHABLE) {
            summary = "left the water at " + source.x() + " " + source.y() + " " + source.z();
            return TaskStatus.FAILED;
        }
        BlockPos left = hands.recoverSource(blockPos());
        summary = left == null
                ? "took the water back"
                : "left the water at " + source.x() + " " + source.y() + " " + source.z();
        return left == null ? TaskStatus.SUCCESS : TaskStatus.FAILED;
    }

    @Override
    public void stop(TaskContext c) {
        if (approach != null) {
            approach.stop(c.companion());
        }
    }

    @Override
    public String describe() {
        return "taking the water back at " + source.x() + " " + source.y() + " " + source.z();
    }

    @Override
    public String resultSummary() {
        return summary;
    }

    private BlockPos blockPos() {
        return new BlockPos(source.x(), source.y(), source.z());
    }
}
