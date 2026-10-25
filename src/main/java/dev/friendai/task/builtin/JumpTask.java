package dev.friendai.task.builtin;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;

/** Jumps once from the ground and waits for the landing, so a plan can chain jumps or move on afterwards. */
public final class JumpTask implements Task {
    private static final int GROUND_WAIT_TICKS = 40;
    private static final int LANDING_WAIT_TICKS = 60;

    private int ticks;
    private boolean airborne;
    private String summary = "";

    @Override
    public String type() {
        return "jump";
    }

    @Override
    public void start(TaskContext c) {}

    @Override
    public TaskStatus tick(TaskContext c) {
        CompanionEntity companion = c.companion();
        ticks++;
        if (!airborne) {
            if (companion.onGround()) {
                companion.jumpFromGround();
                airborne = true;
                return TaskStatus.RUNNING;
            }
            if (ticks > GROUND_WAIT_TICKS) {
                summary = "I couldn't find ground to jump from";
                return TaskStatus.FAILED;
            }
            return TaskStatus.RUNNING;
        }
        // A fall can be long; report the jump rather than fail when it takes a while to land.
        if (companion.onGround() || ticks > GROUND_WAIT_TICKS + LANDING_WAIT_TICKS) {
            summary = "jumped";
            return TaskStatus.SUCCESS;
        }
        return TaskStatus.RUNNING;
    }

    @Override
    public void stop(TaskContext c) {}

    @Override
    public String describe() {
        return "jumping";
    }

    @Override
    public String resultSummary() {
        return summary;
    }
}
