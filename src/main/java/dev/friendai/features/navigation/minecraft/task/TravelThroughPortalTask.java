package dev.friendai.features.navigation.minecraft.task;

import dev.friendai.features.navigation.minecraft.nav.MoveHelper;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Walks into an existing lit nether portal and waits for vanilla to move the companion. It does not call a dimension
 * teleport and never builds a frame: entering the portal block is what lets the game apply its own portal rules and
 * destination search, and a missing portal is an ordinary failure the owner can act on.
 */
public final class TravelThroughPortalTask implements Task {
    private static final int SEARCH_RADIUS = 16;
    private static final int ENTER_TIMEOUT = 200;
    /** The body must overlap the portal block for the game to notice it, so the walk stops inside the frame. */
    private static final double ARRIVE_IN_PORTAL = 0.3;

    private final String targetDimension;
    private final MoveHelper mover = new MoveHelper(1.0, ARRIVE_IN_PORTAL, false);
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

    /** Whether a portal trip can ever be what the order asked for: only the two vanilla portal worlds. */
    public static boolean isSupportedDestination(String dimension) {
        String value = normalize(dimension);
        return value.equals("minecraft:overworld") || value.equals("minecraft:the_nether");
    }

    @Override
    public void start(TaskContext c) {
        mover.reset(c);
        portal = nearestPortal(c.level(), c.companion().blockPosition());
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
        if (entered) {
            enteredTicks++;
            if (enteredTicks > ENTER_TIMEOUT) {
                summary = "the portal did not move the companion";
                return TaskStatus.FAILED;
            }
            return TaskStatus.RUNNING;
        }
        Vec3 center = new Vec3(portal.getX() + 0.5, portal.getY(), portal.getZ() + 0.5);
        return switch (mover.tick(c, center)) {
            case ARRIVED -> {
                // The body is inside the frame now; vanilla owns the rest.
                mover.stop(c);
                entered = true;
                enteredTicks = 0;
                yield TaskStatus.RUNNING;
            }
            case STUCK -> {
                summary = "can't walk into the portal";
                yield TaskStatus.FAILED;
            }
            case MOVING -> TaskStatus.RUNNING;
        };
    }

    /**
     * The nearest portal block around {@code origin}. Unloaded columns are skipped: reading a block loads its chunk,
     * so a hunt over a cold area would generate every chunk in the radius on the server thread.
     */
    public static BlockPos nearestPortal(ServerLevel level, BlockPos origin) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                if (!level.hasChunkAt(new BlockPos(origin.getX() + dx, origin.getY(), origin.getZ() + dz))) {
                    continue;
                }
                for (int dy = -4; dy <= 4; dy++) {
                    BlockPos at = origin.offset(dx, dy, dz);
                    if (!level.getBlockState(at).is(Blocks.NETHER_PORTAL)) {
                        continue;
                    }
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
        String value = dimension.strip().toLowerCase(Locale.ROOT);
        return value.contains(":") ? value : "minecraft:" + value;
    }

    @Override
    public void stop(TaskContext c) {
        mover.stop(c);
        c.companion().getNavigation().stop();
    }

    /**
     * Vanilla moves the companion on the tick its box overlaps the portal block, before the walk reports arrival, so
     * the success path ends with a live navigator holding the body. Drop it like any other finished task.
     */
    @Override
    public void onTerminal(TaskContext c) {
        mover.stop(c);
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
