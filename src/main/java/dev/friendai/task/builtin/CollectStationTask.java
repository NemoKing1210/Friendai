package dev.friendai.task.builtin;

import dev.friendai.crafting.Station;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.crafting.Stations;
import dev.friendai.task.mining.BlockBreaker;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

/**
 * Walks back to a station the companion placed for a craft and did not need again, and puts it in the inventory. It
 * runs quietly in place of the default activity, so the companion looks after its things without interrupting the
 * brain.
 */
public final class CollectStationTask implements Task {
    /** How long the companion waits for the station's drop to appear and be picked up after the block breaks. */
    private static final int SETTLE_TICKS = 20;
    private static final double PICKUP_RADIUS = 4.0;

    private final Station station;
    private final BlockPos pos;
    private BlockBreaker breaker;
    private int settleTicks;
    private String summary = "";

    public CollectStationTask(Station station, BlockPos pos) {
        this.station = station;
        this.pos = pos;
    }

    @Override
    public String type() {
        return "collect_station";
    }

    @Override
    public boolean emitsEvents() {
        return false;
    }

    @Override
    public TaskStatus tick(TaskContext c) {
        Block block = Stations.block(station);
        if (breaker == null) {
            if (block == null || !c.level().getBlockState(pos).is(block)) {
                summary = "the " + station.noun() + " at " + coords() + " is gone";
                return TaskStatus.SUCCESS;
            }
            breaker = new BlockBreaker(pos, state -> state.is(block));
        }
        BlockBreaker.State state = breaker.tick(c);
        if (state == BlockBreaker.State.BROKEN) {
            // The drop lands a tick or two after the block breaks: stay until it is in the inventory.
            c.companion().collectNearbyItems(PICKUP_RADIUS);
            if (++settleTicks < SETTLE_TICKS) {
                return TaskStatus.RUNNING;
            }
            summary = "put the " + station.noun() + " back";
            return TaskStatus.SUCCESS;
        }
        return switch (state) {
            case WORKING -> TaskStatus.RUNNING;
            case GONE -> {
                summary = "the " + station.noun() + " at " + coords() + " is gone";
                yield TaskStatus.SUCCESS;
            }
            case UNREACHABLE -> {
                summary = "couldn't get back to the " + station.noun() + " at " + coords();
                yield TaskStatus.FAILED;
            }
            case OUT_OF_SIGHT -> {
                summary = "couldn't see the " + station.noun() + " at " + coords();
                yield TaskStatus.FAILED;
            }
            case PROTECTED -> {
                summary = "the " + station.noun() + " at " + coords() + " is protected";
                yield TaskStatus.FAILED;
            }
            case BROKEN -> throw new IllegalStateException("handled above");
        };
    }

    @Override
    public void start(TaskContext c) {}

    @Override
    public void stop(TaskContext c) {
        if (breaker != null) {
            breaker.stop(c);
            breaker = null;
        }
    }

    @Override
    public String describe() {
        return "fetching back the " + station.noun() + " at " + coords();
    }

    @Override
    public String resultSummary() {
        return summary;
    }

    private String coords() {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}
