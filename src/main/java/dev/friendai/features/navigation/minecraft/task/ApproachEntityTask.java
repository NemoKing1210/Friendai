package dev.friendai.features.navigation.minecraft.task;

import dev.friendai.features.navigation.minecraft.nav.EntityApproach;
import dev.friendai.features.navigation.minecraft.nav.EntityTargets;
import dev.friendai.features.navigation.minecraft.nav.ResourceJourney;
import dev.friendai.foundation.spatial.Pos;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import net.minecraft.world.entity.Entity;

/** Navigation-owned approach action used by the movement tool without constructing an interaction task. */
public final class ApproachEntityTask implements Task {
    private static final int LOOK_TICKS = 40;

    private final Entity target;
    private final double distance;
    private final EntityApproach approach;
    private String summary = "";
    private static final double JOURNEY_DISTANCE = 64.0;
    private static final double JOURNEY_TARGET_MARGIN = 16.0;
    private ResourceJourney journey;
    private Pos journeyTarget;

    public ApproachEntityTask(Entity target, double distance) {
        this.target = target;
        this.distance = distance;
        this.approach = new EntityApproach(distance);
    }

    @Override
    public String type() {
        return "approach";
    }

    @Override
    public void start(TaskContext context) {
        approach.reset(context);
    }

    @Override
    public TaskStatus tick(TaskContext context) {
        if (target.level() != context.level()) {
            journeyStop(context);
            return approachTick(context);
        }
        double distance = context.companion().position().distanceTo(target.position());
        if (distance > JOURNEY_DISTANCE) {
            if (journey == null
                    || journeyTarget == null
                    || target.position()
                                    .distanceToSqr(journeyTarget.x() + 0.5, journeyTarget.y(), journeyTarget.z() + 0.5)
                            > JOURNEY_TARGET_MARGIN * JOURNEY_TARGET_MARGIN) {
                journeyStop(context);
                var settings = context.resourceSearchSettings();
                journeyTarget = new Pos((int) Math.floor(target.getX()), (int) Math.floor(target.getY()), (int)
                        Math.floor(target.getZ()));
                journey = new ResourceJourney(
                        context,
                        context.level().dimension().identifier().toString(),
                        journeyTarget,
                        null,
                        context.ownerKnowledge().trails(),
                        settings.allowExpeditions() && settings.chunkLoadingEnabled());
            }
            switch (journey.tick(context)) {
                case ARRIVED -> journeyStop(context);
                case BLOCKED, CANCELLED -> {
                    journeyStop(context);
                    summary = "can't reach the " + EntityTargets.label(target) + " across the loaded edge";
                    return TaskStatus.FAILED;
                }
                case WAITING_FOR_CHUNK, WORKING -> {
                    return TaskStatus.RUNNING;
                }
            }
        } else {
            journeyStop(context);
        }
        return approachTick(context);
    }

    private TaskStatus approachTick(TaskContext context) {
        return switch (approach.tick(context, target)) {
            case TARGET_GONE -> {
                summary = "the " + EntityTargets.label(target) + " is gone";
                yield TaskStatus.FAILED;
            }
            case UNREACHABLE -> {
                summary = "can't reach the " + EntityTargets.label(target);
                yield TaskStatus.FAILED;
            }
            case REACHED -> {
                context.companion().body().holdLook(target::getEyePosition, LOOK_TICKS);
                summary = "standing " + Math.round(context.companion().distanceTo(target)) + " blocks from the "
                        + EntityTargets.label(target);
                yield TaskStatus.SUCCESS;
            }
            case MOVING -> TaskStatus.RUNNING;
        };
    }

    @Override
    public void stop(TaskContext context) {
        journeyStop(context);
        approach.stop(context);
    }

    @Override
    public void onTerminal(TaskContext context) {
        // A finished or failed approach is not ticked again, so the route it left running would drive the body on its
        // own; the terminal hook is the only place that closes it for a task the coordinator removed without a stop.
        journeyStop(context);
        approach.stop(context);
    }

    private void journeyStop(TaskContext context) {
        if (journey != null) journey.stop(context);
        journey = null;
        journeyTarget = null;
    }

    @Override
    public String describe() {
        return "walking up to the " + EntityTargets.label(target) + " (to within " + distance + " blocks)";
    }

    @Override
    public String resultSummary() {
        return summary;
    }
}
