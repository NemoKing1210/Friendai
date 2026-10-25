package dev.friendai.task.builtin;

import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.inventory.DropCollector;
import dev.friendai.task.mining.BlockBreaker;
import dev.friendai.task.mining.BlockMatcher;
import dev.friendai.task.mining.TreeFinder;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * Fells one tree chosen when the task was made: the lowest remaining log first, one log at a time, until none is
 * left, the limit is reached or the rest cannot be reached. It never picks a second tree.
 */
public final class FellTreeTask implements Task {
    /** How far from the foot of the tree the sweep after it looks for the drops. */
    private static final int SWEEP_RADIUS = 8;
    /** How close a drop must be to be taken without walking, while the job goes on. */
    private static final double FEET_RADIUS = 1.5;

    private final TreeFinder.TreeInfo tree;
    private final BlockMatcher matcher;
    private final int limit;
    private final boolean collect;
    private final String label;

    private final Set<BlockPos> remaining;
    /** Every log this task has ever counted, so a log found later is counted once. */
    private final Set<BlockPos> known;
    private final Set<BlockPos> blacklist = new HashSet<>();
    private int total;
    private int felled;
    private BlockBreaker breaker;
    private DropCollector sweep;
    private TaskStatus pendingStatus;
    private BlockPos lastBroken;
    private String summary = "";

    /**
     * @param limit how many logs to cut at most; 0 means the whole tree
     * @param collect whether to walk around picking the drops up afterwards; drops at the feet are always taken
     */
    public FellTreeTask(TreeFinder.TreeInfo tree, BlockMatcher matcher, int limit, boolean collect) {
        this.tree = tree;
        this.matcher = matcher;
        this.limit = limit;
        this.collect = collect;
        this.remaining = new LinkedHashSet<>(tree.logs());
        this.known = new HashSet<>(tree.logs());
        this.total = tree.logs().size();
        this.label = tree.type().endsWith("_log") ? tree.type().substring(0, tree.type().length() - 4) : tree.type();
    }

    @Override
    public String type() {
        return "fell_tree";
    }

    @Override
    public void start(TaskContext c) {}

    @Override
    public TaskStatus tick(TaskContext c) {
        DropCollector.pickupAtFeet(c, FEET_RADIUS, null);
        if (sweep != null) {
            if (sweep.tick(c) == DropCollector.State.DONE) {
                sweep = null;
                return pendingStatus;
            }
            return TaskStatus.RUNNING;
        }
        remaining.removeIf(pos -> !c.level().hasChunkAt(pos) || !matcher.test(c.level().getBlockState(pos)));
        if (breaker == null) {
            return next(c);
        }
        switch (breaker.tick(c)) {
            case WORKING -> {}
            case BROKEN -> {
                BlockPos target = breaker.target();
                lastBroken = target;
                breaker = null;
                felled++;
                remaining.remove(target);
                adopt(c, target);
            }
            case GONE -> breaker = null;
            case UNREACHABLE -> {
                blacklist.addAll(breaker.candidates());
                breaker = null;
            }
            case PROTECTED, OUT_OF_SIGHT -> {
                // The breaker names the block it stood by when it knows one, else the only candidate it had.
                blacklist.addAll(breaker.target() != null ? List.of(breaker.target()) : breaker.candidates());
                breaker = null;
            }
        }
        return TaskStatus.RUNNING;
    }

    /** Picks the next log, or finishes when the limit is reached or nothing reachable is left. */
    private TaskStatus next(TaskContext c) {
        if (limit > 0 && felled >= limit) {
            return finish(c, TaskStatus.SUCCESS, "felled " + count() + " " + label + " logs (limit)");
        }
        BlockPos pick = null;
        for (BlockPos pos : remaining) {
            if (blacklist.contains(pos)) {
                continue;
            }
            if (pick == null || pos.getY() < pick.getY()
                    || (pos.getY() == pick.getY()
                            && pos.distSqr(c.companion().blockPosition()) < pick.distSqr(c.companion().blockPosition()))) {
                pick = pos;
            }
        }
        if (pick != null) {
            // One candidate at a time is what keeps the order bottom-up: with several, the approach would break
            // whichever is nearest.
            breaker = new BlockBreaker(List.of(pick), matcher::test, true, true, true);
            return TaskStatus.RUNNING;
        }
        long unreached = remaining.stream().filter(blacklist::contains).count();
        if (unreached > 0) {
            String text = felled == 0
                    ? "could not reach any " + label + " logs"
                    : "felled " + felled + " of " + total + " " + label + " logs; " + unreached + " could not be reached";
            return finish(c, TaskStatus.FAILED, text);
        }
        return finish(c, TaskStatus.SUCCESS, "felled " + count() + " " + label + " logs");
    }

    /** "5", or "3 of 14" when part of the tree went some other way. */
    private String count() {
        return felled == total ? String.valueOf(felled) : felled + " of " + total;
    }

    private TaskStatus finish(TaskContext c, TaskStatus status, String text) {
        summary = text;
        if (!collect) {
            return status;
        }
        // The drops of a high log land at the foot of the tree, so the sweep is centred on the ground there.
        BlockPos from = lastBroken != null ? lastBroken : tree.base();
        sweep = new DropCollector(Vec3.atCenterOf(new BlockPos(from.getX(), tree.base().getY(), from.getZ())), SWEEP_RADIUS);
        pendingStatus = status;
        return TaskStatus.RUNNING;
    }

    /** Takes in logs next to the one just broken that the first scan did not see, such as past an unloaded chunk. */
    private void adopt(TaskContext c, BlockPos broken) {
        for (Direction side : Direction.values()) {
            BlockPos near = broken.relative(side);
            if (!known.contains(near)
                    && c.level().hasChunkAt(near)
                    && matcher.test(c.level().getBlockState(near))) {
                BlockPos pos = near.immutable();
                known.add(pos);
                remaining.add(pos);
                total++;
            }
        }
    }

    @Override
    public void stop(TaskContext c) {
        if (sweep != null) {
            sweep.stop(c);
            sweep = null;
        }
        if (breaker != null) {
            breaker.stop(c);
            breaker = null;
        } else {
            c.companion().getNavigation().stop();
        }
    }

    @Override
    public String describe() {
        BlockPos base = tree.base();
        return "felling " + label + " tree at " + base.getX() + " " + base.getY() + " " + base.getZ();
    }

    @Override
    public String resultSummary() {
        return summary;
    }
}
