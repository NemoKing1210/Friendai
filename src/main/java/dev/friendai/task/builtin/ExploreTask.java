package dev.friendai.task.builtin;

import dev.friendai.crafting.obtain.Spiral;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.mining.MiningBounds;
import dev.friendai.pathing.Node;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.interact.PlaceSpot;
import dev.friendai.task.inventory.InventoryOps;
import dev.friendai.task.inventory.ItemMatcher;
import dev.friendai.task.mining.BlockMatcher;
import dev.friendai.task.mining.BlockSearch;
import dev.friendai.task.mining.MiningSession;
import dev.friendai.task.nav.MoveHelper;
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
    private final int undergroundRadius;
    private final int maxBlocks;
    private final int maxTicks;

    private final MoveHelper mover = new MoveHelper(1.0, 1.5);

    private Vec3 origin;
    private String summary = "";
    private int ticks;
    private int blocksDug;
    private int noProgress;
    private int lastTorchBlockCount;
    private int branchSteps;
    private BlockPos previousFoot;
    private boolean failed;

    private Spiral spiral;
    private Vec3 waypoint;

    private Direction heading;
    private MiningSession miningSession;

    public ExploreTask(BlockMatcher target, boolean down, int depthY, int maxRadius, int maxBlocks, int maxTicks) {
        this.target = target;
        this.down = down;
        this.depthY = depthY;
        this.maxRadius = Math.max(LEG, maxRadius);
        this.undergroundRadius = Math.max(0, maxRadius);
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
            if (miningSession != null) {
                miningSession.stop(c);
            }
            return TaskStatus.SUCCESS;
        }
        return down ? digTick(c) : surfaceTick(c);
    }

    /** Number of blocks actually removed by this bounded exploration leg. */
    public int excavationBlocks() {
        return blocksDug;
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
                mover.reset(c);
                if (found(c.level(), companion)) {
                    return TaskStatus.SUCCESS;
                }
            }
            case STUCK -> {
                waypoint = null;
                mover.reset(c);
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
        if (miningSession == null) {
            BlockPos start = companion.blockPosition();
            miningSession = new MiningSession(companion,
                    new MiningBounds(new Node(start.getX(), start.getY(), start.getZ()), undergroundRadius),
                    maxBlocks, maxTicks);
            int legalTargetY = Math.max(c.level().getMinY() + 1, Math.min(depthY, c.level().getMaxY() - 2));
            miningSession.explore(legalTargetY, headingIndex(heading));
        }
        MiningSession.State state = miningSession.tick(c);
        blocksDug = miningSession.brokenBlocks();
        branchSteps = miningSession.completedExplorationSteps();
        if (blocksDug - lastTorchBlockCount >= TORCH_EVERY) {
            placeTorch(c.level(), companion, companion.blockPosition());
            lastTorchBlockCount = blocksDug;
        }
        if (branchSteps >= BRANCH || blocksDug >= maxBlocks || state == MiningSession.State.EXHAUSTED
                || state == MiningSession.State.FAILED) {
            return finishFailure(c);
        }
        return TaskStatus.RUNNING;
    }

    private static int headingIndex(Direction direction) {
        return switch (direction) {
            case NORTH -> 0;
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> 0;
        };
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
        var evidence = dev.friendai.core.FriendaiServerState.get(level.getServer()).resources();
        return evidence.knownOrVisible(companion, target, down ? 6 : Math.min(16, maxRadius), 1).stream().findFirst()
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
        if (miningSession != null) {
            miningSession.stop(c);
        }
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
        mover.stop(c);
        c.companion().getNavigation().stop();
        if (miningSession != null) {
            miningSession.stop(c);
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
