package dev.friedai.task.builtin;

import dev.friedai.task.Task;
import dev.friedai.task.TaskContext;
import dev.friedai.task.TaskStatus;
import net.minecraft.server.level.ServerPlayer;

/** Stays close to the owner. Never finishes by itself. */
public final class FollowTask implements Task {
    private static final double STOP_DISTANCE = 3.0;
    private static final double START_DISTANCE = 5.0;
    private static final double TELEPORT_DISTANCE = 24.0;

    private final boolean quiet;
    private boolean moving;
    private int ticks;

    /** @param quiet true when started as the default activity, so the brain hears nothing about it */
    public FollowTask(boolean quiet) {
        this.quiet = quiet;
    }

    @Override
    public String type() {
        return "follow";
    }

    @Override
    public void start(TaskContext c) {}

    @Override
    public TaskStatus tick(TaskContext c) {
        ServerPlayer owner = c.owner().orElse(null);
        if (owner == null || owner.level() != c.level()) {
            return TaskStatus.RUNNING;
        }
        var companion = c.companion();
        double distance = companion.distanceTo(owner);
        if (distance > TELEPORT_DISTANCE && owner.onGround()) {
            companion.teleportTo(owner.getX(), owner.getY(), owner.getZ());
            companion.getNavigation().stop();
            moving = false;
            return TaskStatus.RUNNING;
        }
        if (moving && distance <= STOP_DISTANCE) {
            moving = false;
            companion.getNavigation().stop();
        } else if (!moving && distance > START_DISTANCE) {
            moving = true;
        }
        if (moving && ticks++ % 10 == 0) {
            companion.getNavigation().moveTo(owner, 1.0);
        }
        return TaskStatus.RUNNING;
    }

    @Override
    public void stop(TaskContext c) {
        c.companion().getNavigation().stop();
    }

    @Override
    public String describe() {
        return "following the owner";
    }

    @Override
    public String resultSummary() {
        return "stopped following";
    }

    @Override
    public boolean emitsEvents() {
        return !quiet;
    }

    @Override
    public boolean hasTimeout() {
        return false;
    }

    @Override
    public String saveData() {
        return "follow";
    }
}
