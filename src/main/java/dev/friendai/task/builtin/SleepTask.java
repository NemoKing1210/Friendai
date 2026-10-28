package dev.friendai.task.builtin;

import dev.friendai.core.FriendaiServerState;
import dev.friendai.features.navigation.minecraft.nav.Approach;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.mining.BlockMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.attribute.BedRule;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.jetbrains.annotations.Nullable;

/** Walks to a bed and sleeps in it, until morning or until woken or told to get up. */
public final class SleepTask implements Task {
    private static final int BED_SEARCH_RADIUS = 24;

    @Nullable
    private final BlockPos requestedBed;

    private final boolean untilMorning;
    private final boolean automatic;
    private final List<BlockPos> automaticBeds;
    private int nextAutomaticBed;
    private BlockPos bed;
    private Approach approach;
    private boolean asleep;
    private float healthAtSleep;
    private String summary = "";

    /**
     * @param requestedBed the bed to use, or null for the nearest one
     * @param untilMorning whether to get up when the day comes; otherwise sleeps until told to stop
     */
    public SleepTask(@Nullable BlockPos requestedBed, boolean untilMorning) {
        this(requestedBed, untilMorning, false, List.of());
    }

    /** Internal autonomous sleep; does not notify the brain when the task completes. */
    public SleepTask(@Nullable BlockPos requestedBed, boolean untilMorning, boolean automatic) {
        this(
                requestedBed,
                untilMorning,
                automatic,
                automatic && requestedBed != null ? List.of(requestedBed) : List.of());
    }

    private SleepTask(
            @Nullable BlockPos requestedBed, boolean untilMorning, boolean automatic, List<BlockPos> automaticBeds) {
        this.requestedBed = requestedBed;
        this.untilMorning = untilMorning;
        this.automatic = automatic;
        this.automaticBeds = List.copyOf(automaticBeds);
    }

    public static SleepTask automatic(List<BlockPos> beds) {
        return new SleepTask(null, true, true, beds);
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
            if (c.companion().getHealth() < healthAtSleep || c.companion().getTarget() != null || !isBed(c, bed)) {
                c.companion().stopSleeping();
                summary = "woke up because the bed was unsafe";
                return TaskStatus.SUCCESS;
            }
            if (untilMorning && shouldWakeForMorning(c)) {
                c.companion().stopSleeping();
                summary = "slept until morning";
                return TaskStatus.SUCCESS;
            }
            return TaskStatus.RUNNING;
        }
        if (untilMorning && shouldWakeForMorning(c)) {
            summary = "it is daytime, there is nothing to sleep through";
            return TaskStatus.FAILED;
        }
        if (bed == null) {
            bed = automatic ? nextAutomaticBed(c) : requestedBed != null ? head(c, requestedBed) : findBed(c);
            if (bed == null) {
                summary = requestedBed != null ? "there is no bed at that spot" : "I can't find a bed nearby";
                return TaskStatus.FAILED;
            }
            approach = new Approach(bed);
        }
        switch (approach.tick(c)) {
            case UNREACHABLE -> {
                if (automatic) {
                    bed = null;
                    approach = null;
                    return TaskStatus.RUNNING;
                }
                summary = "can't reach the bed";
                return TaskStatus.FAILED;
            }
            case MOVING -> {
                return TaskStatus.RUNNING;
            }
            case IN_REACH -> {}
        }
        if (!c.companion().startSleeping(bed)) {
            if (automatic) {
                bed = null;
                approach = null;
                return TaskStatus.RUNNING;
            }
            summary = "couldn't lie down in that bed";
            return TaskStatus.FAILED;
        }
        asleep = true;
        healthAtSleep = c.companion().getHealth();
        BlockPos foot = foot(c, bed);
        if (foot != null && c.companion().ownerUuid() != null) {
            FriendaiServerState.get(c.level().getServer())
                    .companionLife()
                    .setRespawnPoint(
                            c.companion().ownerUuid(),
                            new dev.friendai.entity.CompanionLifeData.RespawnPoint(
                                    c.level().dimension(), foot));
        }
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

    @Override
    public boolean emitsEvents() {
        return !automatic;
    }

    /** Finds accessible, unoccupied beds in distance order without reading unloaded chunks. */
    public static List<BlockPos> findNearbyBeds(ServerLevel level, BlockPos center) {
        if (!bedsWork(level)) {
            return List.of();
        }
        List<BlockPos> beds = new ArrayList<>();
        for (int x = -16; x <= 16; x++) {
            for (int y = -16; y <= 16; y++) {
                for (int z = -16; z <= 16; z++) {
                    double distance = x * x + y * y + z * z;
                    if (distance > 16 * 16) {
                        continue;
                    }
                    BlockPos foot = center.offset(x, y, z);
                    if (!level.hasChunkAt(foot)) {
                        continue;
                    }
                    BlockState state = level.getBlockState(foot);
                    if (!state.is(BlockTags.BEDS)
                            || !state.hasProperty(BlockStateProperties.BED_PART)
                            || !state.hasProperty(HorizontalDirectionalBlock.FACING)
                            || state.getValue(BlockStateProperties.BED_PART) != BedPart.FOOT) {
                        continue;
                    }
                    BlockPos head = foot.relative(state.getValue(HorizontalDirectionalBlock.FACING));
                    if (!level.hasChunkAt(head)) {
                        continue;
                    }
                    BlockState headState = level.getBlockState(head);
                    if (!headState.is(BlockTags.BEDS)
                            || !headState.hasProperty(BedBlock.OCCUPIED)
                            || headState.getValue(BedBlock.OCCUPIED)) {
                        continue;
                    }
                    if (!hasSafeApproach(level, foot, head)) {
                        continue;
                    }
                    beds.add(foot);
                }
            }
        }
        beds.sort(Comparator.comparingDouble(pos -> pos.distSqr(center)));
        return List.copyOf(beds);
    }

    public static boolean isNight(ServerLevel level) {
        return level.isDarkOutside();
    }

    /** Whether beds can be slept in at all in this dimension, by the dimension's bed rule. */
    public static boolean bedsWork(ServerLevel level) {
        BedRule rule = level.environmentAttributes().getDimensionValue(EnvironmentAttributes.BED_RULE);
        return rule.canSleep() != BedRule.Rule.NEVER;
    }

    private boolean shouldWakeForMorning(TaskContext c) {
        return automatic ? !isNight(c.level()) : c.level().isBrightOutside();
    }

    private @Nullable BlockPos nextAutomaticBed(TaskContext c) {
        while (nextAutomaticBed < automaticBeds.size()) {
            BlockPos candidate = head(c, automaticBeds.get(nextAutomaticBed++));
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean hasSafeApproach(ServerLevel level, BlockPos foot, BlockPos head) {
        for (BlockPos bedHalf : new BlockPos[] {foot, head}) {
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos stand = bedHalf.relative(direction);
                BlockPos below = stand.below();
                if (level.hasChunkAt(stand)
                        && level.hasChunkAt(stand.above())
                        && level.hasChunkAt(below)
                        && level.getBlockState(stand).isAir()
                        && level.getBlockState(stand.above()).isAir()
                        && level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The head half of the bed at pos (the half a sleeper lies on), or null if there is no bed there. */
    private static @Nullable BlockPos head(TaskContext c, BlockPos pos) {
        BlockState state = c.level().getBlockState(pos);
        if (!state.is(BlockTags.BEDS)
                || !state.hasProperty(BlockStateProperties.BED_PART)
                || !state.hasProperty(HorizontalDirectionalBlock.FACING)) {
            return null;
        }
        return state.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT
                ? pos.relative(state.getValue(HorizontalDirectionalBlock.FACING))
                : pos;
    }

    private static @Nullable BlockPos foot(TaskContext c, BlockPos head) {
        BlockState state = c.level().getBlockState(head);
        if (!state.is(BlockTags.BEDS)
                || !state.hasProperty(BlockStateProperties.BED_PART)
                || !state.hasProperty(HorizontalDirectionalBlock.FACING)) {
            return null;
        }
        if (state.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT) {
            return head;
        }
        Direction facing = state.getValue(HorizontalDirectionalBlock.FACING);
        return head.relative(facing.getOpposite());
    }

    private static boolean isBed(TaskContext c, BlockPos head) {
        BlockPos foot = foot(c, head);
        if (foot == null) {
            return false;
        }
        BlockState footState = c.level().getBlockState(foot);
        BlockState headState = c.level().getBlockState(head);
        return footState.is(BlockTags.BEDS)
                && headState.is(BlockTags.BEDS)
                && footState.hasProperty(BlockStateProperties.BED_PART)
                && headState.hasProperty(BlockStateProperties.BED_PART)
                && footState.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT
                && headState.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD
                && footState.getValue(HorizontalDirectionalBlock.FACING)
                        == headState.getValue(HorizontalDirectionalBlock.FACING);
    }

    private static @Nullable BlockPos findBed(TaskContext c) {
        BlockMatcher beds = BlockMatcher.parse("#minecraft:beds").orElse(null);
        if (beds == null) return null;
        var resources = FriendaiServerState.get(c.level().getServer()).resources();
        return resources.knownOrVisible(c.companion(), beds, BED_SEARCH_RADIUS, 16).stream()
                .map(p -> head(c, p))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }
}
