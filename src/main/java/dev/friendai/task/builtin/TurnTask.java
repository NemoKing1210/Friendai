package dev.friendai.task.builtin;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.navigation.movement.Facing;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import org.jetbrains.annotations.Nullable;

/** Turns on the spot to a compass direction or by an angle, without moving. */
public final class TurnTask implements Task {
    private static final float STEP_DEGREES = 30.0f;
    private static final float DONE_TOLERANCE = 1.0f;

    @Nullable
    private final Float absoluteYaw;

    private final float relativeDegrees;
    private final String description;
    private float target;
    private String summary = "";

    private TurnTask(@Nullable Float absoluteYaw, float relativeDegrees, String description) {
        this.absoluteYaw = absoluteYaw;
        this.relativeDegrees = relativeDegrees;
        this.description = description;
    }

    /** Turns to face a compass direction. */
    public static TurnTask toYaw(float yaw, String label) {
        return new TurnTask(Facing.normalize(yaw), 0.0f, "turning to face " + label);
    }

    /** Turns by an angle; positive turns to the right (clockwise seen from above). */
    public static TurnTask by(float degrees) {
        return new TurnTask(null, degrees, "turning " + Math.round(degrees) + " degrees");
    }

    @Override
    public String type() {
        return "turn";
    }

    @Override
    public void start(TaskContext c) {
        CompanionEntity companion = c.companion();
        companion.getNavigation().stop();
        // Yaw grows clockwise seen from above (south 0, west 90), so a turn to the right adds to it.
        target = absoluteYaw != null ? absoluteYaw : Facing.normalize(companion.getYRot() + relativeDegrees);
    }

    @Override
    public TaskStatus tick(TaskContext c) {
        CompanionEntity companion = c.companion();
        float yaw = Facing.stepToward(companion.getYRot(), target, STEP_DEGREES);
        companion.facing().aimYaw(yaw, 2);
        if (Facing.difference(yaw, target) <= DONE_TOLERANCE) {
            summary = "now facing " + Facing.compassName(yaw);
            return TaskStatus.SUCCESS;
        }
        return TaskStatus.RUNNING;
    }

    @Override
    public void stop(TaskContext c) {}

    @Override
    public String describe() {
        return description;
    }

    @Override
    public String resultSummary() {
        return summary;
    }
}
