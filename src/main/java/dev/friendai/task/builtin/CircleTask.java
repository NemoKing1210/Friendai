package dev.friendai.task.builtin;

import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.nav.MoveHelper;
import java.util.function.Supplier;
import net.minecraft.world.phys.Vec3;

/** Walks a ring around a point or an entity, looking at its centre, for a number of laps. */
public final class CircleTask implements Task {
    private static final int POINTS_PER_LAP = 8;
    private static final int LOOK_TICKS = 3;
    private static final int MAX_SKIPPED = 3;

    private final Supplier<Vec3> center;
    private final String centerLabel;
    private final double radius;
    private final int laps;
    private final MoveHelper mover = new MoveHelper(1.0, 1.0);
    private double startAngle;
    private int point;
    private int skipped;
    private String summary = "";

    public CircleTask(Supplier<Vec3> center, String centerLabel, double radius, int laps) {
        this.center = center;
        this.centerLabel = centerLabel;
        this.radius = radius;
        this.laps = laps;
    }

    @Override
    public String type() {
        return "circle";
    }

    @Override
    public void start(TaskContext c) {
        Vec3 middle = center.get();
        Vec3 me = c.companion().position();
        startAngle = middle == null ? 0.0 : Math.atan2(me.z - middle.z, me.x - middle.x);
        mover.reset(c);
    }

    @Override
    public TaskStatus tick(TaskContext c) {
        Vec3 middle = center.get();
        if (middle == null) {
            summary = "the " + centerLabel + " is gone";
            return TaskStatus.FAILED;
        }
        if (point >= POINTS_PER_LAP * laps) {
            summary = "walked " + laps + " lap" + (laps == 1 ? "" : "s") + " around the " + centerLabel;
            return TaskStatus.SUCCESS;
        }
        c.companion().body().holdLook(center, LOOK_TICKS);
        double angle = startAngle + 2.0 * Math.PI * (point + 1) / POINTS_PER_LAP;
        Vec3 waypoint = new Vec3(
                middle.x + Math.cos(angle) * radius, c.companion().getY(), middle.z + Math.sin(angle) * radius);
        switch (mover.tick(c.companion(), waypoint)) {
            case ARRIVED -> {
                point++;
                mover.reset(c);
            }
            case STUCK -> {
                if (++skipped > MAX_SKIPPED) {
                    summary = "something keeps blocking the way around the " + centerLabel;
                    return TaskStatus.FAILED;
                }
                point++;
                mover.reset(c);
            }
            default -> { }
        }
        return TaskStatus.RUNNING;
    }

    @Override
    public void stop(TaskContext c) {
        mover.stop(c);
        c.companion().body().releaseLook();
    }

    @Override
    public String describe() {
        return "walking around the " + centerLabel + " at " + radius + " blocks";
    }

    @Override
    public String resultSummary() {
        return summary;
    }
}
