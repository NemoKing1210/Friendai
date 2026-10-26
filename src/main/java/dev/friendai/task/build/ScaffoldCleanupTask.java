package dev.friendai.task.build;

import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import java.util.List;
import net.minecraft.core.BlockPos;

/**
 * Takes down the scaffold blocks a cancelled build left in the world, highest first. Only a recorded position that
 * still holds dirt or cobblestone is broken, so a block a player put there is never touched.
 */
public final class ScaffoldCleanupTask implements Task {
    private final List<BlockPos> positions;
    private Scaffold scaffold;
    private String summary = "";

    public ScaffoldCleanupTask(List<BlockPos> positions) {
        this.positions = List.copyOf(positions);
    }

    @Override
    public String type() {
        return "scaffold_cleanup";
    }

    @Override
    public boolean hasTimeout() {
        return false;
    }

    @Override
    public boolean emitsEvents() {
        return false;
    }

    @Override
    public void start(TaskContext c) {}

    @Override
    public TaskStatus tick(TaskContext c) {
        if (scaffold == null) {
            scaffold = new Scaffold(c.companion());
            scaffold.adopt(positions);
        }
        return switch (scaffold.removeAll()) {
            case CLIMBING -> TaskStatus.RUNNING;
            case READY -> {
                summary = "removed " + scaffold.used() + " scaffold blocks";
                yield TaskStatus.SUCCESS;
            }
            case FAILED -> {
                summary = "couldn't remove " + scaffold.left() + " scaffold blocks: " + scaffold.failure();
                yield TaskStatus.FAILED;
            }
        };
    }

    @Override
    public void stop(TaskContext c) {
        if (scaffold != null) {
            scaffold.stop();
        }
    }

    @Override
    public String describe() {
        return "taking down its scaffold";
    }

    @Override
    public String resultSummary() {
        return summary;
    }
}
