package dev.friendai.features.navigation.minecraft.task;

import dev.friendai.features.navigation.minecraft.nav.MoveHelper;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Walks into an existing lit nether portal and waits for vanilla to move the companion. It does not call a dimension
 * teleport: entering the portal block is what lets the game apply its own portal rules and destination search.
 */
public final class TravelThroughPortalTask implements Task {
    private static final int SEARCH_RADIUS = 16;
    private static final int ENTER_TIMEOUT = 200;

    private final String targetDimension;
    private final MoveHelper mover = new MoveHelper(1.0, 1.5);
    private BlockPos portal;
    private boolean entered;
    private int enteredTicks;
    private String summary = "";

    public TravelThroughPortalTask(String targetDimension) {
        this.targetDimension = targetDimension == null ? "" : targetDimension.strip();
    }

    @Override
    public String type() {
        return "travel_through_portal";
    }

    @Override
    public void start(TaskContext c) {
        mover.reset(c);
        portal = nearestPortal(c);
    }

    @Override
    public TaskStatus tick(TaskContext c) {
        String dimension = c.level().dimension().identifier().toString();
        if (dimension.equals(normalize(targetDimension))) {
            summary = "arrived in " + dimension;
            return TaskStatus.SUCCESS;
        }
        if (!dimension.equals("minecraft:overworld") && !dimension.equals("minecraft:the_nether")) {
            summary = "portal travel is not supported from " + dimension;
            return TaskStatus.FAILED;
        }
        if (portal == null) {
            summary = "no lit nether portal within " + SEARCH_RADIUS + " blocks";
            return TaskStatus.FAILED;
        }
        Vec3 center = new Vec3(portal.getX() + 0.5, portal.getY(), portal.getZ() + 0.5);
        if (c.companion().position().distanceTo(center) > 2.0) {
            mover.tick(c, center);
            return TaskStatus.RUNNING;
        }
        if (!entered) {
            c.companion().teleportTo(center.x, center.y, center.z);
            entered = true;
            enteredTicks = 0;
            return TaskStatus.RUNNING;
        }
        enteredTicks++;
        if (enteredTicks > ENTER_TIMEOUT) {
            summary = "the portal did not move the companion";
            return TaskStatus.FAILED;
        }
        return TaskStatus.RUNNING;
    }

    private static BlockPos nearestPortal(TaskContext c) {
        BlockPos origin = c.companion().blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                    BlockPos at = origin.offset(dx, dy, dz);
                    if (!c.level().getBlockState(at).is(Blocks.NETHER_PORTAL)) continue;
                    double distance = at.distSqr(origin);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = at;
                    }
                }
            }
        }
        return best;
    }

    private static String normalize(String dimension) {
        if (dimension == null || dimension.isBlank()) return "";
        String value = dimension.strip().toLowerCase(java.util.Locale.ROOT);
        return value.contains(":") ? value : "minecraft:" + value;
    }

    @Override
    public void stop(TaskContext c) {
        mover.stop(c);
        c.companion().getNavigation().stop();
    }

    @Override
    public String describe() {
        return "walking into a nether portal toward " + targetDimension;
    }

    @Override
    public String resultSummary() {
        return summary;
    }

    @Override
    public String saveData() {
        return targetDimension;
    }
}
