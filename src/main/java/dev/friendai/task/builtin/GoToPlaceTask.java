package dev.friendai.task.builtin;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.places.Place;
import dev.friendai.place.PlaceService;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.nav.MoveHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Walks to a remembered place and counts the visit when the companion gets there. */
public final class GoToPlaceTask implements Task {
    private final Place place;
    private final Vec3 target;
    private final double successRadius;
    private final MoveHelper mover = new MoveHelper(1.0, 1.5);
    private String summary = "";

    public GoToPlaceTask(Place place) {
        this.place = place;
        this.target = Vec3.atCenterOf(new BlockPos(
                place.anchor().x(), place.anchor().y(), place.anchor().z()));
        this.successRadius = Math.max(2, Math.min(place.radius(), 8));
    }

    @Override
    public String type() {
        return "go_to_place";
    }

    @Override
    public void start(TaskContext c) {
        mover.reset(c);
    }

    @Override
    public TaskStatus tick(TaskContext c) {
        CompanionEntity companion = c.companion();
        switch (mover.tick(companion, target)) {
            case ARRIVED -> {
                double horizontal = Math.hypot(companion.getX() - target.x, companion.getZ() - target.z);
                if (horizontal > successRadius) {
                    summary = "can't get closer to " + place.name();
                    return TaskStatus.FAILED;
                }
                PlaceService.markVisit(companion, place.id(), companion.level().getGameTime());
                summary = "reached " + place.name();
                return TaskStatus.SUCCESS;
            }
            case STUCK -> {
                summary = "can't reach " + place.name();
                return TaskStatus.FAILED;
            }
            default -> {
                return TaskStatus.RUNNING;
            }
        }
    }

    @Override
    public void stop(TaskContext c) {
        mover.stop(c);
    }

    @Override
    public String describe() {
        return "walking to " + place.name();
    }

    @Override
    public String resultSummary() {
        return summary;
    }
}
