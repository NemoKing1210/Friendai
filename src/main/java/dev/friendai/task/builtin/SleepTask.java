package dev.friendai.task.builtin;

import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.nav.Approach;
import java.util.Comparator;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.jetbrains.annotations.Nullable;

/** Walks to a bed and sleeps in it, until morning or until woken or told to get up. */
public final class SleepTask implements Task {
    private static final int BED_SEARCH_RADIUS = 24;
    private static final int BED_SEARCH_HEIGHT = 6;

    @Nullable private final BlockPos requestedBed;
    private final boolean untilMorning;
    private BlockPos bed;
    private Approach approach;
    private boolean asleep;
    private String summary = "";

    /**
     * @param requestedBed the bed to use, or null for the nearest one
     * @param untilMorning whether to get up when the day comes; otherwise sleeps until told to stop
     */
    public SleepTask(@Nullable BlockPos requestedBed, boolean untilMorning) {
        this.requestedBed = requestedBed;
        this.untilMorning = untilMorning;
    }

    @Override
    public String type() {
        return "sleep";
    }

    @Override
    public void start(TaskContext c) {}

    @Override
    public TaskStatus tick(TaskContext c) {
        if (asleep) {
            if (!c.companion().isSleeping()) {
                summary = "woke up";
                return TaskStatus.SUCCESS;
            }
            if (untilMorning && c.level().isBrightOutside()) {
                c.companion().stopSleeping();
                summary = "slept until morning";
                return TaskStatus.SUCCESS;
            }
            return TaskStatus.RUNNING;
        }
        if (untilMorning && c.level().isBrightOutside()) {
            summary = "it is daytime, there is nothing to sleep through";
            return TaskStatus.FAILED;
        }
        if (bed == null) {
            bed = requestedBed != null ? head(c, requestedBed) : findBed(c);
            if (bed == null) {
                summary = requestedBed != null ? "there is no bed at that spot" : "I can't find a bed nearby";
                return TaskStatus.FAILED;
            }
            approach = new Approach(bed);
        }
        switch (approach.tick(c.companion())) {
            case UNREACHABLE -> {
                summary = "can't reach the bed";
                return TaskStatus.FAILED;
            }
            case MOVING -> {
                return TaskStatus.RUNNING;
            }
            case IN_REACH -> {
            }
        }
        if (!c.companion().startSleeping(bed)) {
            summary = "couldn't lie down in that bed";
            return TaskStatus.FAILED;
        }
        asleep = true;
        return TaskStatus.RUNNING;
    }

    @Override
    public void stop(TaskContext c) {
        if (c.companion().isSleeping()) {
            c.companion().stopSleeping();
        }
    }

    @Override
    public String describe() {
        return asleep ? "sleeping" : "going to bed";
    }

    @Override
    public String resultSummary() {
        return summary;
    }

    @Override
    public boolean hasTimeout() {
        return !asleep;
    }

    /** The head half of the bed at pos (the half a sleeper lies on), or null if there is no bed there. */
    private static @Nullable BlockPos head(TaskContext c, BlockPos pos) {
        BlockState state = c.level().getBlockState(pos);
        if (!state.is(BlockTags.BEDS)) {
            return null;
        }
        if (state.hasProperty(BlockStateProperties.BED_PART)
                && state.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT) {
            return pos.relative(state.getValue(HorizontalDirectionalBlock.FACING));
        }
        return pos;
    }

    private static @Nullable BlockPos findBed(TaskContext c) {
        BlockPos origin = c.companion().blockPosition();
        Optional<BlockPos> nearest = BlockPos.betweenClosedStream(
                        origin.offset(-BED_SEARCH_RADIUS, -BED_SEARCH_HEIGHT, -BED_SEARCH_RADIUS),
                        origin.offset(BED_SEARCH_RADIUS, BED_SEARCH_HEIGHT, BED_SEARCH_RADIUS))
                .filter(p -> c.level().getBlockState(p).is(BlockTags.BEDS))
                .map(BlockPos::immutable)
                .min(Comparator.comparingDouble(origin::distSqr));
        return nearest.map(p -> head(c, p)).orElse(null);
    }
}
