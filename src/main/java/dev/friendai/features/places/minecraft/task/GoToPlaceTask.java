package dev.friendai.features.places.minecraft.task;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.navigation.minecraft.nav.MoveHelper;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Walks to a remembered place and counts the visit when the companion gets there. */
public final class GoToPlaceTask implements Task {
    private final String placeName;
    private final Vec3 target;
    private final double successRadius;
    private final Consumer<TaskContext> onVisit;
    private final MoveHelper mover = new MoveHelper(1.0, 1.5);
    private String summary = "";

    public GoToPlaceTask(String placeName, BlockPos target, int radius, Consumer<TaskContext> onVisit) {
        this.placeName = placeName;
        this.target = Vec3.atCenterOf(target);
        this.successRadius = Math.max(2, Math.min(radius, 8));
        this.onVisit = onVisit;
    }

    @Override
    public String type() {
        return "go_to_place";
    }

    @Override
    public void start(TaskContext context) {
        mover.reset(context);
    }

    @Override
    public TaskStatus tick(TaskContext context) {
        CompanionEntity companion = context.companion();
        switch (mover.tick(context, target)) {
            case ARRIVED -> {
                double horizontal = Math.hypot(companion.getX() - target.x, companion.getZ() - target.z);
                if (horizontal > successRadius) {
                    summary = "can't get closer to " + placeName;
                    return TaskStatus.FAILED;
                }
                onVisit.accept(context);
                summary = "reached " + placeName;
                return TaskStatus.SUCCESS;
            }
            case STUCK -> {
                summary = "can't reach " + placeName;
                return TaskStatus.FAILED;
            }
            default -> {
                return TaskStatus.RUNNING;
            }
        }
    }

    @Override
    public void stop(TaskContext context) {
        mover.stop(context);
    }

    @Override
    public String describe() {
        return "walking to " + placeName;
    }

    @Override
    public String resultSummary() {
        return summary;
    }
}
