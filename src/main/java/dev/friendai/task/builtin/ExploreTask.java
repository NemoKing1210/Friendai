package dev.friendai.task.builtin;

import dev.friendai.crafting.obtain.Spiral;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.interact.PlaceSpot;
import dev.friendai.task.inventory.InventoryOps;
import dev.friendai.task.inventory.ItemMatcher;
import dev.friendai.task.mining.BlockBreaker;
import dev.friendai.task.mining.BlockMatcher;
import dev.friendai.task.mining.BlockSearch;
import dev.friendai.task.nav.MoveHelper;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Looks for a block that is not nearby: a square spiral over the surface, or a safe staircase down to an ore's
 * depth and a short branch tunnel. Stops at its own radius, block and tick limits; never digs next to lava or
 * flowing water and never steps off a drop deeper than three blocks.
 */
public final class ExploreTask implements Task {
    private static final int LEG = 16;
    private static final int BRANCH = 32;
    private static final int TORCH_EVERY = 8;
    private static final int MAX_NO_PROGRESS = 200;

    private final BlockMatcher target;
    private final boolean down;
    private final int depthY;
    private final int maxRadius;
    private final int maxBlocks;
    private final int maxTicks;

    private final MoveHelper mover = new MoveHelper(1.0, 1.5);
    private final Deque<BlockPos> toBreak = new ArrayDeque<>();

    private Vec3 origin;
    private String summary = "";
    private int ticks;
    private int blocksDug;
    private int noProgress;
    private int stalled;
    private boolean failed;

    private Spiral spiral;
    private Vec3 waypoint;

    private Direction heading;
    private BlockBreaker breaker;
    private Vec3 walkTo;
    private int walkTicks;
    private boolean atDepth;
    private int branchSteps;
    private BlockPos previousFoot;

    public ExploreTask(BlockMatcher target, boolean down, int depthY, int maxRadius, int maxBlocks, int maxTicks) {
        this.target = target;
        this.down = down;
        this.depthY = depthY;
        this.maxRadius = Math.max(LEG, maxRadius);
        this.maxBlocks = maxBlocks;
        this.maxTicks = maxTicks;
    }

    @Override
    public String type() {
        return "explore";
    }

    @Override
    public boolean hasTimeout() {
        return false;
    }

    @Override
    public void start(TaskContext c) {
        ServerLevel level = c.level();
        BlockPos start = c.companion().blockPosition();
        origin = Vec3.atBottomCenterOf(start);
        spiral = new Spiral(origin.x, origin.z, LEG, maxRadius);
        heading = c.companion().getDirection();
        waypoint = origin;
        c.companion().taskRunner().notice(down
                ? "going down to Y " + depthY + " to look for " + target.label()
                : "exploring up to " + maxRadius + " blocks around to look for " + target.label());
        if (found(level, c.companion())) {
            return;
        }
    }

    @Override
    public TaskStatus tick(TaskContext c) {
        if (failed) {
            return TaskStatus.FAILED;
        }
        if (!summary.isEmpty()) {
            return TaskStatus.SUCCESS;
        }
        if (++ticks > maxTicks) {
            return finishFailure(c);
        }
        if ((down ? ticks % 4 == 0 : ticks % 16 == 0) && found(c.level(), c.companion())) {
            return TaskStatus.SUCCESS;
        }
        return down ? digTick(c) : surfaceTick(c);
    }

    private TaskStatus surfaceTick(TaskContext c) {
        CompanionEntity companion = c.companion();
        if (companion.blockPosition().equals(previousFoot)) {
            noProgress++;
        } else {
            previousFoot = companion.blockPosition();
            noProgress = 0;
            if (ticks % 16 == 0 && found(c.level(), companion)) {
                return TaskStatus.SUCCESS;
            }
        }
        if (noProgress > MAX_NO_PROGRESS) {
            return finishFailure(c);
        }
        if (waypoint == null) {
            waypoint = nextWaypoint();
            if (waypoint == null) {
                return finishFailure(c);
            }
        }
        switch (mover.tick(companion, waypoint)) {
            case ARRIVED -> {
                waypoint = null;
                mover.reset();
                if (found(c.level(), companion)) {
                    return TaskStatus.SUCCESS;
                }
            }
            case STUCK -> {
                waypoint = null;
                mover.reset();
            }
            default -> {
            }
        }
        return TaskStatus.RUNNING;
    }

    private @Nullable Vec3 nextWaypoint() {
        var next = spiral.next();
        return next.map(point -> new Vec3(point[0], origin.y, point[1])).orElse(null);
    }

    private TaskStatus digTick(TaskContext c) {
        CompanionEntity companion = c.companion();
        ServerLevel level = c.level();
        if (++stalled > MAX_NO_PROGRESS * 2) {
            return finishFailure(c);
        }
        if (breaker != null) {
            BlockBreaker.State state = breaker.tick(c);
            switch (state) {
                case BROKEN -> {
                    blocksDug++;
                    stalled = 0;
                    breaker = null;
                }
                case GONE -> breaker = null;
                case UNREACHABLE, PROTECTED, OUT_OF_SIGHT -> {
                    breaker.stop(c);
                    breaker = null;
                    turn();
                }
                default -> {
                }
            }
            return TaskStatus.RUNNING;
        }
        if (!toBreak.isEmpty()) {
            BlockPos next = toBreak.peek();
            if (level.getBlockState(next).isAir()) {
                toBreak.poll();
                return TaskStatus.RUNNING;
            }
            breaker = new BlockBreaker(next, state -> !state.isAir());
            return TaskStatus.RUNNING;
        }
        if (walkTo != null) {
            double horizontal = Math.hypot(companion.getX() - walkTo.x, companion.getZ() - walkTo.z);
            if (horizontal <= 0.7 && Math.abs(companion.getY() - walkTo.y) <= 0.8) {
                walkTo = null;
                walkTicks = 0;
                stalled = 0;
                companion.getNavigation().stop();
                noProgress = 0;
                if (previousFoot != null && blocksDug > 0 && blocksDug % TORCH_EVERY == 0) {
                    placeTorch(level, companion, previousFoot);
                }
                previousFoot = companion.blockPosition();
                return TaskStatus.RUNNING;
            }
            companion.pace().update(false, Double.NaN);
            if (walkTicks++ % 10 == 0) {
                companion.getNavigation().moveTo(walkTo.x, walkTo.y, walkTo.z, 1.0);
            }
            if (walkTicks > MAX_NO_PROGRESS) {
                walkTo = null;
                walkTicks = 0;
                companion.getNavigation().stop();
                turn();
            }
            return TaskStatus.RUNNING;
        }
        if (++noProgress > MAX_NO_PROGRESS) {
            return finishFailure(c);
        }
        BlockPos current = companion.blockPosition();
        if (!atDepth && down && current.getY() <= depthY + 2) {
            atDepth = true;
        }
        if (atDepth && branchSteps >= BRANCH) {
            return finishFailure(c);
        }
        if (blocksDug >= maxBlocks) {
            return finishFailure(c);
        }
        BlockPos foot = atDepth ? current.relative(heading) : current.relative(heading).below();
        if (!safe(level, foot)) {
            turn();
            return TaskStatus.RUNNING;
        }
        BlockPos head = foot.above();
        toBreak.clear();
        if (!level.getBlockState(head).isAir()) {
            toBreak.add(head.immutable());
        }
        if (!level.getBlockState(foot).isAir()) {
            toBreak.add(foot.immutable());
        }
        walkTo = Vec3.atBottomCenterOf(foot);
        walkTicks = 0;
        if (atDepth) {
            branchSteps++;
        }
        return TaskStatus.RUNNING;
    }

    /** Right, then left, then back: a staircase that meets lava tries another way. */
    private void turn() {
        heading = heading.getClockWise();
        noProgress++;
    }

    private boolean safe(ServerLevel level, BlockPos foot) {
        if (foot.getY() <= level.getMinY() || foot.getY() >= level.getMaxY()) {
            return false;
        }
        BlockPos head = foot.above();
        for (BlockPos cell : List.of(foot, head)) {
            if (level.getBlockState(cell).getDestroySpeed(level, cell) < 0) {
                return false;
            }
            for (Direction direction : Direction.values()) {
                BlockState neighbour = level.getBlockState(cell.relative(direction));
                if (neighbour.is(Blocks.LAVA) || neighbour.is(Blocks.WATER)) {
                    return false;
                }
            }
        }
        int drop = 0;
        BlockPos below = foot.below();
        while (drop <= 3 && level.getBlockState(below).isAir()) {
            drop++;
            below = below.below();
        }
        return drop <= 3;
    }

    private void placeTorch(ServerLevel level, CompanionEntity companion, BlockPos pos) {
        ItemMatcher torches = ItemMatcher.parse("minecraft:torch").orElseThrow();
        if (InventoryOps.count(companion.inventory(), torches) == 0) {
            return;
        }
        PlaceSpot.spotAt(level, pos).ifPresent(spot -> {
            List<ItemStack> taken = InventoryOps.remove(companion.inventory(), torches, 1);
            if (taken.isEmpty()) {
                return;
            }
            companion.hands().place(taken.get(0), spot.against(), spot.face());
        });
    }

    private boolean found(ServerLevel level, CompanionEntity companion) {
        BlockPos origin = companion.blockPosition();
        return BlockSearch.nearest(level, origin, down ? 6 : Math.min(16, maxRadius), target, Set.of())
                .map(pos -> {
                    BlockState state = level.getBlockState(pos);
                    summary = "found " + BuiltInRegistries.BLOCK.getKey(state.getBlock()) + " at "
                            + pos.getX() + " " + pos.getY() + " " + pos.getZ();
                    return true;
                })
                .orElse(false);
    }

    private TaskStatus finishFailure(TaskContext c) {
        failed = true;
        if (down) {
            summary = "dug " + blocksDug + " blocks down to Y " + c.companion().blockPosition().getY()
                    + " without finding " + target.label();
        } else {
            summary = "no " + target.label() + " within " + maxRadius + " blocks";
        }
        return TaskStatus.FAILED;
    }

    @Override
    public void stop(TaskContext c) {
        c.companion().getNavigation().stop();
        if (breaker != null) {
            breaker.stop(c);
            breaker = null;
        }
    }

    @Override
    public String describe() {
        return down
                ? "digging down to Y " + depthY + " looking for " + target.label()
                : "exploring up to " + maxRadius + " blocks around looking for " + target.label();
    }

    @Override
    public String resultSummary() {
        return summary;
    }
}
