package dev.friendai.task.builtin;

import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.nav.FlightNavigator;
import java.util.Locale;
import net.minecraft.world.phys.Vec3;

/** Collision-aware, bounded creative flight to a loaded point in the current dimension. */
public final class FlyToTask implements Task {
    private final Vec3 target;
    private final FlightNavigator navigator = new FlightNavigator();
    private String summary = "";

    public FlyToTask(Vec3 target) {
        if (target == null
                || !Double.isFinite(target.x)
                || !Double.isFinite(target.y)
                || !Double.isFinite(target.z)
                || Math.abs(target.x) > 30_000_000
                || Math.abs(target.z) > 30_000_000) {
            throw new IllegalArgumentException("flight target must be finite and inside the world border range");
        }
        this.target = target;
    }

    @Override
    public String type() {
        return "fly_to";
    }

    @Override
    public void start(TaskContext context) {
        summary = "";
    }

    @Override
    public TaskStatus tick(TaskContext context) {
        return switch (navigator.tick(context, target)) {
            case ARRIVED -> {
                summary = "flew to " + coords();
                yield TaskStatus.SUCCESS;
            }
            case UNREACHABLE -> {
                summary = "can't reach " + coords() + ": " + navigator.diagnostic();
                yield TaskStatus.FAILED;
            }
            case SEARCHING, MOVING -> TaskStatus.RUNNING;
        };
    }

    @Override
    public void stop(TaskContext context) {
        navigator.stop(context);
    }

    @Override
    public String describe() {
        return "flying to " + coords();
    }

    @Override
    public String resultSummary() {
        return summary;
    }

    public String navigationDebug() {
        return navigator.debugStatus();
    }

    @Override
    public String saveData() {
        return null;
    }

    private String coords() {
        return String.format(Locale.ROOT, "%.1f %.1f %.1f", target.x, target.y, target.z);
    }
}
